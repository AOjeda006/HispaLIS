package es.hispalis.integracion.mllp;

import ca.uhn.hl7v2.util.StandardSocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * El TLS del lado que <em>llama</em>: hacia el HIS.
 *
 * <p>Es una clase aparte de {@link FabricaDeSocketsTls} porque el material es distinto y confundirlos
 * cuesta un rato de depuración: el receptor necesita un <strong>almacén de claves</strong> con su
 * certificado de servidor; el emisor necesita un <strong>almacén de confianza</strong> con el del
 * servidor al que llama. Compartir la fábrica haría que el motor saliera presentando su certificado
 * de servidor, que no es lo que un cliente hace.
 *
 * <h2>Deuda conocida, y por qué se asume así</h2>
 *
 * <p>Con {@code verificar-certificado} apagado —el valor de desarrollo— este cliente acepta cualquier
 * certificado. Es lo que hace falta contra un HIS simulado con un autofirmado generado al vuelo, y
 * <strong>no es aceptable fuera de desarrollo</strong>: un cliente que no verifica tiene un canal
 * cifrado contra quien sea. Encenderlo es cambiar una propiedad, y el arranque avisa cuando está
 * apagado.
 */
public class FabricaDeSocketsDeSalida extends StandardSocketFactory {

    private final SSLContext contexto;

    public FabricaDeSocketsDeSalida(PropiedadesDelHis destino) {
        this.contexto = contextoPara(destino);
    }

    @Override
    public Socket createTlsSocket() throws IOException {
        return contexto.getSocketFactory().createSocket();
    }

    @Override
    public ServerSocket createTlsServerSocket() throws IOException {
        // Este motor no escucha por aquí: el listener tiene su propia fábrica, con su almacén de
        // claves. Devolver un socket de servidor sin certificado sería peor que negarse.
        throw new UnsupportedOperationException(
                "La fábrica de salida no abre servidores; el listener usa FabricaDeSocketsTls.");
    }

    private static SSLContext contextoPara(PropiedadesDelHis destino) {
        try {
            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(null, destino.verificarCertificado() ? null : new TrustManager[] {ACEPTA_CUALQUIERA}, null);
            return contexto;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar el TLS de salida hacia el HIS", e);
        }
    }

    /** Solo para desarrollo. Ver la nota de la clase. */
    private static final X509TrustManager ACEPTA_CUALQUIERA = new X509TrustManager() {

        @Override
        public void checkClientTrusted(X509Certificate[] cadena, String tipo) {
            // No se comprueba nada: este motor no acepta conexiones entrantes por esta fábrica.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] cadena, String tipo) {
            // Desarrollo: el HIS simulado presenta un autofirmado generado en el propio build.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
