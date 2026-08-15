package es.hispalis.integracion.arnes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Un emisor MLLP que escribe <strong>los bytes que se le den</strong>, sin sobre y sin librería.
 *
 * <p>Existe porque {@link ClienteMllpDePrueba} usa el LLP de HAPI, y el LLP de HAPI es exactamente lo
 * que hay que poder saltarse aquí: pone el {@code 0x0B} al principio y el {@code 0x1C 0x0D} al final,
 * así que con él <strong>no se puede</strong> mandar un sobre a medias, dos comienzos seguidos, un
 * final sin principio ni un byte nulo dentro del texto. Un fuzzer que solo llegue hasta donde llega
 * el cliente bueno no está probando el borde: está probando el cliente.
 *
 * <p>La lectura de la respuesta también va a mano y con plazo. Interesa distinguir <em>tres</em>
 * finales, y la librería los confundiría en una excepción: hubo acuse, el servidor cerró sin decir
 * nada, o el servidor sigue esperando más bytes —que es lo correcto cuando el sobre no se ha
 * cerrado—. Solo el primero es un acuse; el tercero no es un cuelgue del servidor mientras el
 * siguiente mensaje por una conexión nueva siga entrando.
 */
public final class EmisorCrudoMllp implements AutoCloseable {

    /** Principio de bloque: el sobre MLLP empieza aquí. */
    public static final byte INICIO = 0x0B;

    /** Fin de bloque. */
    public static final byte FIN = 0x1C;

    /** Retorno de carro: cierra el sobre detrás del {@link #FIN}. */
    public static final byte RETORNO = 0x0D;

    private final Socket socket;

    private EmisorCrudoMllp(Socket socket) {
        this.socket = socket;
    }

    /** Abre una conexión TLS contra el listener, como haría un HIS. */
    public static EmisorCrudoMllp conectar(String servidor, int puerto, int plazoMs) {
        try {
            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(null, new TrustManager[] {new ConfiaEnCualquiera()}, new SecureRandom());
            Socket socket = contexto.getSocketFactory().createSocket(servidor, puerto);
            socket.setSoTimeout(plazoMs);
            return new EmisorCrudoMllp(socket);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo abrir la conexión cruda contra el listener MLLP", e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar el TLS del emisor crudo", e);
        }
    }

    /**
     * Cómo terminó un envío. Son tres finales y <strong>no valen lo mismo</strong>.
     *
     * <p>La diferencia entre los dos últimos es la que decide si hay defecto o no. Con la conexión
     * <strong>cerrada</strong>, el emisor se entera en el acto de que la entrega ha fallado: su socket
     * muere, no da el mensaje por entregado y vuelve a conectar. Con la conexión <strong>abierta y
     * muda</strong>, se queda bloqueado hasta su propio plazo sin saber si el laboratorio lo procesó o
     * no, que es exactamente el estado que la regla «siempre se responde» existe para impedir.
     */
    public enum Cierre {
        /** Contestó. */
        ACUSE,
        /** No contestó, pero cerró: el emisor se entera. */
        CONEXION_CERRADA,
        /** Ni contestó ni cerró. El emisor se queda esperando. */
        SILENCIO
    }

    /**
     * @param cierre cómo terminó
     * @param acuse lo que contestó, sin los bytes de sobre; vacío si no contestó
     */
    public record Respuesta(Cierre cierre, Optional<byte[]> acuse) {

        public boolean hayAcuse() {
            return cierre == Cierre.ACUSE;
        }
    }

    /**
     * Escribe los bytes tal cual y espera la respuesta.
     *
     * <p>El acuse se devuelve en <strong>bytes y no en texto</strong> a propósito. Con entrada hostil,
     * el {@code MSH-18} del acuse es lo que fuera el del mensaje roto, así que no se sabe de antemano
     * con qué juego viene codificado; decodificar aquí con uno elegido a dedo convertiría una fuga de
     * filiación en mojibake y el test que la busca pasaría en verde. Quien compare decide, y compara
     * con los dos.
     *
     * @param bytes lo que viaja por el hilo, sobre incluido o no: aquí no se añade nada
     */
    public Respuesta enviar(byte[] bytes) {
        try {
            OutputStream salida = socket.getOutputStream();
            salida.write(bytes);
            salida.flush();
            return leerRespuesta();
        } catch (IOException cerroElOtroLado) {
            return new Respuesta(Cierre.CONEXION_CERRADA, Optional.empty());
        }
    }

    /** El acuse leído con los dos juegos que puede traer, para buscar en los dos. */
    public static String comoTexto(byte[] acuse, Charset juego) {
        return new String(acuse, juego);
    }

    /** Lo mismo en latín-1 y en UTF-8, concatenado: sirve para buscar un literal sin adivinar. */
    public static String enCualquierJuego(byte[] acuse) {
        return comoTexto(acuse, StandardCharsets.ISO_8859_1) + "\n" + comoTexto(acuse, StandardCharsets.UTF_8);
    }

    /**
     * Lee hasta encontrar el fin de bloque, hasta que cierren o hasta agotar el plazo.
     *
     * <p>Devuelve lo leído <strong>sin</strong> los bytes de sobre, para que el test compare contra el
     * texto del acuse y no contra sus adornos.
     */
    private Respuesta leerRespuesta() throws IOException {
        InputStream entrada = socket.getInputStream();
        java.io.ByteArrayOutputStream leido = new java.io.ByteArrayOutputStream();
        boolean cerro = false;
        try {
            int octeto;
            while (true) {
                octeto = entrada.read();
                if (octeto == -1) {
                    cerro = true;
                    break;
                }
                if (octeto == FIN) {
                    entrada.read(); // el retorno de carro que cierra el sobre
                    break;
                }
                if (octeto != INICIO) {
                    leido.write(octeto);
                }
            }
        } catch (SocketTimeoutException seAgotoElPlazo) {
            return new Respuesta(leido.size() == 0 ? Cierre.SILENCIO : Cierre.ACUSE, deLoLeido(leido));
        }
        if (leido.size() > 0) {
            return new Respuesta(Cierre.ACUSE, deLoLeido(leido));
        }
        return new Respuesta(cerro ? Cierre.CONEXION_CERRADA : Cierre.SILENCIO, Optional.empty());
    }

    private static Optional<byte[]> deLoLeido(java.io.ByteArrayOutputStream leido) {
        return leido.size() == 0 ? Optional.empty() : Optional.of(leido.toByteArray());
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException yaEstaba) {
            // Se estaba cerrando de todas formas.
        }
    }

    /** Solo para pruebas: el servidor presenta el autofirmado que genera este mismo build. */
    private static final class ConfiaEnCualquiera implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] cadena, String tipo) {
            // Sin comprobación: es el certificado de este build.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] cadena, String tipo) {
            // Ídem.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
