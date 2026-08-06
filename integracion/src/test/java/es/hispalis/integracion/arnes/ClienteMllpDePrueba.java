package es.hispalis.integracion.arnes;

import ca.uhn.hl7v2.llp.ExtendedMinLowerLayerProtocol;
import ca.uhn.hl7v2.llp.HL7Reader;
import ca.uhn.hl7v2.llp.HL7Writer;
import ca.uhn.hl7v2.llp.LLPException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Un emisor MLLP de usar y tirar: manda un mensaje y devuelve el acuse, en crudo.
 *
 * <p>Usa el <strong>mismo LLP «extendido» de HAPI</strong> que el servidor, así que el
 * <em>framing</em> tampoco se escribe a mano aquí, y —esto es lo que hace útil al arnés— el mensaje
 * se codifica en el cable con el juego que declare su propio {@code MSH-18}. Un mensaje con
 * {@code 8859/1} viaja como latín-1 de verdad, byte a byte, no como una cadena de Java que alguien
 * ha decidido cómo convertir.
 *
 * <p>El {@code TrustManager} acepta cualquier certificado. Es de prueba y solo vive en
 * {@code src/test}: el servidor presenta uno autofirmado generado en {@code target/}, y validarlo
 * exigiría montar además un almacén de confianza para no probar nada más.
 */
public final class ClienteMllpDePrueba {

    private final String servidor;
    private final int puerto;
    private final boolean tls;

    public ClienteMllpDePrueba(String servidor, int puerto, boolean tls) {
        this.servidor = servidor;
        this.puerto = puerto;
        this.tls = tls;
    }

    /**
     * Manda el mensaje y espera el acuse.
     *
     * @param mensaje el mensaje HL7 v2 con {@code \r} de separador de segmento
     * @return el acuse tal y como llega
     */
    public String enviar(String mensaje) {
        return enviar(mensaje, null);
    }

    /**
     * Manda el mensaje codificando el cable con un juego <strong>distinto</strong> del que declara.
     *
     * <p>Es el emisor mal configurado, que es el caso real: alguien puso {@code MSH-18} a mano y el
     * sistema sigue serializando como siempre. Se usa {@code MinLLPWriter}, el escritor mínimo de
     * HAPI, que codifica con el juego que se le diga e ignora {@code MSH-18} — el <em>framing</em>
     * lo sigue poniendo la librería.
     *
     * @param charsetReal lo que de verdad viaja por el cable, o {@code null} para respetar
     *     {@code MSH-18}
     */
    public String enviarComo(String mensaje, java.nio.charset.Charset charsetReal) {
        return enviar(mensaje, charsetReal);
    }

    private String enviar(String mensaje, java.nio.charset.Charset charsetReal) {
        try (Socket socket = abrir()) {
            socket.setSoTimeout(10_000);
            ExtendedMinLowerLayerProtocol llp = new ExtendedMinLowerLayerProtocol();
            HL7Writer escritor = charsetReal == null
                    ? llp.getWriter(socket.getOutputStream())
                    : new ca.uhn.hl7v2.llp.MinLLPWriter(socket.getOutputStream(), charsetReal);
            HL7Reader lector = llp.getReader(socket.getInputStream());

            escritor.writeMessage(mensaje);
            return lector.getMessage();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo hablar con el listener MLLP", e);
        } catch (LLPException e) {
            throw new IllegalStateException("Fallo de la capa MLLP", e);
        }
    }

    private Socket abrir() throws IOException {
        if (!tls) {
            return new Socket(servidor, puerto);
        }
        try {
            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(null, new TrustManager[] {new ConfiaEnCualquiera()}, new SecureRandom());
            return contexto.getSocketFactory().createSocket(servidor, puerto);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar el TLS del cliente de prueba", e);
        }
    }

    /** Solo para pruebas: el servidor presenta un autofirmado generado en el propio build. */
    private static final class ConfiaEnCualquiera implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] cadena, String tipo) {
            // Sin comprobación: es el certificado que ha generado este mismo build.
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
