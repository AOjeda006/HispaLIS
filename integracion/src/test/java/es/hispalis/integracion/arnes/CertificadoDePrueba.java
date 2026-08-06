package es.hispalis.integracion.arnes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * El certificado con el que el listener MLLP habla TLS durante las pruebas.
 *
 * <p>Se <strong>genera al vuelo</strong> en {@code target/} y no se guarda en el repositorio. No es
 * remilgo: un almacén de claves commiteado es un secreto commiteado, y da igual que sea de mentira —
 * el día que alguien copie el patrón para el entorno de verdad, el hábito ya está cogido. Además, un
 * certificado en el repositorio caduca y rompe la CI meses después sin que nadie sepa por qué.
 *
 * <p>Lo genera {@code keytool}, que viene con el JDK con el que corren los tests. No hace falta
 * ninguna dependencia criptográfica extra para algo que solo existe mientras dura el build.
 */
public final class CertificadoDePrueba {

    public static final String CLAVE = "hispalis-de-prueba";

    private static final Path ALMACEN = Path.of("target", "tls-de-prueba.p12");

    private CertificadoDePrueba() {
        // Utilidad.
    }

    /** Genera el almacén si no existe y devuelve su ruta. */
    public static Path almacenDeClaves() {
        try {
            if (Files.exists(ALMACEN)) {
                return ALMACEN.toAbsolutePath();
            }
            Files.createDirectories(ALMACEN.getParent());
            ejecutar(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair",
                    "-alias",
                    "hispalis-mllp",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "2",
                    "-dname",
                    "CN=localhost, O=HispaLIS, C=ES",
                    "-ext",
                    "SAN=dns:localhost,ip:127.0.0.1",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    ALMACEN.toString(),
                    "-storepass",
                    CLAVE,
                    "-keypass",
                    CLAVE));
            return ALMACEN.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el certificado de prueba", e);
        }
    }

    private static void ejecutar(List<String> orden) throws IOException {
        Process proceso = new ProcessBuilder(orden).redirectErrorStream(true).start();
        String salida = new String(proceso.getInputStream().readAllBytes());
        try {
            if (proceso.waitFor() != 0) {
                throw new IllegalStateException("keytool falló al generar el certificado de prueba:\n" + salida);
            }
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la generación del certificado", interrumpido);
        }
    }
}
