package es.hispalis.integracion.arnes;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.ErrorCode;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.util.StandardSocketFactory;
import es.hispalis.integracion.hl7.ContextosHl7;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * El HIS visto desde el laboratorio: un listener MLLP que recibe lo que el motor le manda y acusa.
 *
 * <p>Con TLS puesto, como el de verdad. Probar el envío en claro y confiar en que cifrado irá igual es
 * probar otra cosa — y el lado emisor tiene su propia fábrica de sockets, distinta de la del
 * receptor, así que si estuvieran cruzadas solo se vería aquí.
 *
 * <p>El código de acuse es configurable: hace falta para comprobar que un {@code AE} del HIS
 * <strong>no</strong> se toma por entrega buena.
 */
public final class HisDePrueba implements AutoCloseable {

    private final HapiContext contexto;
    private final HL7Service servicio;
    private final int puerto;
    private final List<String> recibidos = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<AcknowledgmentCode> acuse = new AtomicReference<>(AcknowledgmentCode.AA);

    private HisDePrueba(HapiContext contexto, HL7Service servicio, int puerto) {
        this.contexto = contexto;
        this.servicio = servicio;
        this.puerto = puerto;
    }

    /** Arranca en un puerto libre, con TLS. */
    public static HisDePrueba arrancado() {
        int puerto = puertoLibre();
        HapiContext contexto = ContextosHl7.nuevo();
        contexto.setSocketFactory(new SocketsConCertificadoDePrueba());

        HL7Service servicio = contexto.newServer(puerto, true);
        HisDePrueba his = new HisDePrueba(contexto, servicio, puerto);
        servicio.registerApplication(new Recepcion(his));
        try {
            servicio.startAndWait();
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió el arranque del HIS de prueba", interrumpido);
        }
        return his;
    }

    public int puerto() {
        return puerto;
    }

    /** Los mensajes recibidos, tal y como llegaron por el hilo. */
    public List<String> recibidos() {
        return List.copyOf(recibidos);
    }

    /** Hace que el HIS conteste ese código a partir de ahora. */
    public void responder(AcknowledgmentCode codigo) {
        acuse.set(codigo);
    }

    public void olvidarTodo() {
        recibidos.clear();
        acuse.set(AcknowledgmentCode.AA);
    }

    @Override
    public void close() {
        servicio.stopAndWait();
        try {
            contexto.close();
        } catch (IOException seCierraIgual) {
            // El test termina: no hay a quién informar y no hay nada que reparar.
        }
    }

    private static int puertoLibre() {
        try (ServerSocket sonda = new ServerSocket(0)) {
            return sonda.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo reservar un puerto para el HIS de prueba", e);
        }
    }

    private record Recepcion(HisDePrueba his) implements ReceivingApplication<Message> {

        @Override
        public boolean canProcess(Message recibido) {
            return true;
        }

        @Override
        public Message processMessage(Message recibido, Map<String, Object> metadatos) throws HL7Exception {
            try {
                his.recibidos.add(recibido.encode());
                AcknowledgmentCode codigo = his.acuse.get();
                return codigo == AcknowledgmentCode.AA
                        ? recibido.generateACK()
                        : recibido.generateACK(
                                codigo, new HL7Exception("Rechazo de prueba", ErrorCode.APPLICATION_INTERNAL_ERROR));
            } catch (IOException e) {
                throw new HL7Exception("No se pudo componer el acuse del HIS de prueba", e);
            }
        }
    }

    /** El mismo certificado autofirmado que usa el listener del motor; lo genera el propio build. */
    private static final class SocketsConCertificadoDePrueba extends StandardSocketFactory {

        private final SSLContext contexto = contextoTls();

        @Override
        public ServerSocket createTlsServerSocket() throws IOException {
            return contexto.getServerSocketFactory().createServerSocket();
        }

        @Override
        public Socket createTlsSocket() throws IOException {
            return contexto.getSocketFactory().createSocket();
        }

        private static SSLContext contextoTls() {
            char[] clave = CertificadoDePrueba.CLAVE.toCharArray();
            try (InputStream fichero = Files.newInputStream(CertificadoDePrueba.almacenDeClaves())) {
                KeyStore almacen = KeyStore.getInstance("PKCS12");
                almacen.load(fichero, clave);
                KeyManagerFactory claves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                claves.init(almacen, clave);
                SSLContext contexto = SSLContext.getInstance("TLS");
                contexto.init(claves.getKeyManagers(), null, null);
                return contexto;
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("No se pudo preparar el TLS del HIS de prueba", e);
            }
        }
    }
}
