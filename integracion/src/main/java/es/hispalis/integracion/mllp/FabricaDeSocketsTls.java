package es.hispalis.integracion.mllp;

import ca.uhn.hl7v2.util.StandardSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * El material criptográfico del listener MLLP, tomado de un almacén de claves propio.
 *
 * <p>Existe en vez de usar la fábrica estándar de HAPI porque esa se apoya en
 * {@code SSLServerSocketFactory.getDefault()}, que lee las propiedades
 * <strong>globales</strong> {@code javax.net.ssl.keyStore} de la JVM. Configurar TLS por propiedades
 * de sistema tiene dos problemas de los que se pagan tarde: afecta a <em>todo</em> lo que abra un
 * socket en el proceso —el cliente FHIR incluido—, y deja la contraseña del almacén en la línea de
 * órdenes, donde la ve cualquiera que liste procesos.
 *
 * <p>Con un {@link SSLContext} propio, el certificado del canal v2 es del canal v2 y de nada más.
 */
public class FabricaDeSocketsTls extends StandardSocketFactory {

    private final SSLContext contexto;

    public FabricaDeSocketsTls(PropiedadesMllp.Tls configuracion) {
        this.contexto = contextoDesde(configuracion);
    }

    @Override
    public ServerSocket createTlsServerSocket() throws IOException {
        return contexto.getServerSocketFactory().createServerSocket();
    }

    @Override
    public Socket createTlsSocket() throws IOException {
        return contexto.getSocketFactory().createSocket();
    }

    private static SSLContext contextoDesde(PropiedadesMllp.Tls configuracion) {
        if (configuracion.almacenDeClaves() == null
                || configuracion.almacenDeClaves().isBlank()) {
            throw new IllegalStateException(
                    "El listener MLLP está configurado con TLS y no hay almacén de claves. Configura "
                            + "`hispalis.mllp.tls.almacen-de-claves` o apaga TLS explícitamente para pruebas "
                            + "locales: arrancar en claro sin decirlo pondría en la red el nombre y el DNI de "
                            + "cada paciente.");
        }
        char[] clave = configuracion.clave() == null
                ? new char[0]
                : configuracion.clave().toCharArray();
        try (InputStream fichero = Files.newInputStream(Path.of(configuracion.almacenDeClaves()))) {
            KeyStore almacen = KeyStore.getInstance(configuracion.tipo());
            almacen.load(fichero, clave);

            KeyManagerFactory claves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            claves.init(almacen, clave);

            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(claves.getKeyManagers(), null, null);
            return contexto;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "No se pudo preparar el TLS del listener MLLP con el almacén " + configuracion.almacenDeClaves(),
                    e);
        }
    }
}
