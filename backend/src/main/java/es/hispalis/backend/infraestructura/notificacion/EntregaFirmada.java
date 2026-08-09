package es.hispalis.backend.infraestructura.notificacion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * La llamada saliente al receptor, firmada con el secreto compartido.
 *
 * <h2>Por qué firma y no se autentica</h2>
 *
 * <p>Lo habitual en los ejemplos de {@code Subscription} es meter una cabecera
 * {@code Authorization: Bearer …} en {@code Subscription.parameter}. Es un error de diseño y no
 * pequeño: {@code Subscription} <strong>es un recurso más de la API</strong>, así que esa credencial
 * queda legible para cualquiera con permiso de lectura sobre el tipo — y encima queda escrita en el
 * historial de versiones del recurso, que no se borra.
 *
 * <p>Aquí el laboratorio <strong>firma el cuerpo</strong> con HMAC-SHA256 y una clave que vive en su
 * configuración. La {@code Subscription} solo dice cuál se usa, que es un identificador y no un
 * secreto. El receptor recalcula la firma y compara. Además de no publicar nada, esto da algo que un
 * portador no da: la seguridad de que el cuerpo no se ha tocado por el camino.
 *
 * <p><strong>La marca de tiempo va dentro de lo firmado</strong>, y por eso hay dos cabeceras. Sin
 * ella, una notificación capturada se puede reenviar mañana con la misma firma válida; con ella, el
 * receptor descarta lo que llegue con demasiado retraso.
 */
public class EntregaFirmada {

    /** Cabecera con el instante de la firma, en segundos desde el epoch. Entra en lo firmado. */
    public static final String CABECERA_MOMENTO = "X-HispaLIS-Momento";

    /** Cabecera con la firma, en el formato {@code <identificador-de-clave>=sha256:<hex>}. */
    public static final String CABECERA_FIRMA = "X-HispaLIS-Firma";

    private static final String ALGORITMO = "HmacSHA256";

    private final HttpClient http;
    private final Duration tiempoDeEspera;

    public EntregaFirmada(Duration tiempoDeEspera) {
        this.tiempoDeEspera = tiempoDeEspera;
        this.http = HttpClient.newBuilder()
                .connectTimeout(tiempoDeEspera)
                // NUNCA. Un `30x` a otro servidor entregaría la notificación —y su firma— a un sitio
                // que el laboratorio no ha autorizado, sin que nadie lo note.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Entrega el cuerpo y devuelve el código HTTP.
     *
     * @param secreto la clave compartida; {@code null} si no hay ninguna configurada para ese
     *     receptor, en cuyo caso <strong>no se entrega</strong>: mandar sin firmar dejaría al
     *     receptor sin poder distinguir una notificación del laboratorio de una inventada
     * @throws EntregaFallida si el receptor no contesta, contesta un error o no hay con qué firmar
     */
    public int entregar(String destino, String identificadorDeClave, String secreto, String cuerpo) {
        if (secreto == null || secreto.isBlank()) {
            throw new EntregaFallida(
                    "error-response",
                    ("No hay clave compartida configurada para «%s», así que la notificación no sale sin firmar: "
                                    + "el receptor no podría distinguirla de una inventada.")
                            .formatted(identificadorDeClave));
        }
        String momento = String.valueOf(Instant.now().getEpochSecond());

        HttpRequest peticion = HttpRequest.newBuilder(URI.create(destino))
                .timeout(tiempoDeEspera)
                .header("Content-Type", "application/fhir+json")
                .header(CABECERA_MOMENTO, momento)
                .header(CABECERA_FIRMA, identificadorDeClave + "=sha256:" + firma(secreto, momento + "." + cuerpo))
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() / 100 != 2) {
                throw new EntregaFallida(
                        "error-response", "El receptor contestó %d.".formatted(respuesta.statusCode()));
            }
            return respuesta.statusCode();
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new EntregaFallida("no-response", "Interrumpido esperando al receptor.");
        } catch (java.io.IOException noContesta) {
            // El motivo se guarda y acaba saliendo por `$status`. Es técnico y del canal —«Connection
            // refused»—, nunca clínico: aquí no hay nada del paciente que contar.
            throw new EntregaFallida("no-response", noContesta.toString());
        }
    }

    /**
     * HMAC-SHA256 en hexadecimal, que es lo que el receptor recalcula.
     *
     * <p>Es público porque el receptor de pruebas lo usa para verificar. Que la comprobación del test
     * recalcule la firma con esta misma función y no con una copia es deliberado: una copia en el
     * test comprobaría que dos implementaciones coinciden, no que el receptor de verdad puede validar.
     */
    public static String firma(String secreto, String loQueSeFirma) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), ALGORITMO));
            return HexFormat.of().formatHex(mac.doFinal(loQueSeFirma.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException imposible) {
            // HmacSHA256 lo trae toda JVM. Si falta, el problema no es la notificación.
            throw new IllegalStateException("Esta JVM no sabe calcular " + ALGORITMO, imposible);
        }
    }

    /** La entrega no llegó, con el código de {@code subscription-error} que le corresponde. */
    public static class EntregaFallida extends RuntimeException {

        private final String codigo;

        public EntregaFallida(String codigo, String motivo) {
            super(motivo);
            this.codigo = codigo;
        }

        /** {@code no-response} | {@code error-response} | {@code dns-resolution-error}. */
        public String codigo() {
            return codigo;
        }
    }
}
