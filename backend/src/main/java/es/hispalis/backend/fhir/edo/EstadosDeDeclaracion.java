package es.hispalis.backend.fhir.edo;

import es.hispalis.backend.dominio.edo.EstadoDeDeclaracion;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Task.TaskStatus;

/**
 * Cómo se publica el estado de una declaración, que son <strong>dos</strong> elementos y no uno.
 *
 * <p>{@code Task.status} dice cómo va la tarea dentro del laboratorio; {@code Task.businessStatus},
 * cómo va la obligación frente a la administración. Los dos hacen falta y no se pueden fundir: una
 * tarea «en curso» puede ser una declaración enviada y sin acusar, que es la situación que hay que
 * poder nombrar cuando alguien pregunte si se declaró en plazo. Con un solo elemento, «no lo hemos
 * mandado» y «lo mandamos y no contestan» se escriben igual.
 */
public final class EstadosDeDeclaracion {

    /** El {@code system} del vocabulario de negocio, tal y como lo publica la guía. */
    public static final String SYSTEM = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/estados-declaracion-edo";

    private EstadosDeDeclaracion() {
        // Utilidad.
    }

    /** El estado de negocio, codificado. Los códigos son los de {@code estados-declaracion-edo}. */
    public static CodeableConcept aBusinessStatus(EstadoDeDeclaracion estado) {
        return new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem(SYSTEM)
                        .setCode(estado.name())
                        .setDisplay(nombreDe(estado)));
    }

    /**
     * El estado de la tarea, en el vocabulario de FHIR.
     *
     * <p>{@code PENDIENTE} sale como {@code requested} —abierta y sin actuar— y no como {@code ready}:
     * lo segundo diría que la tarea está lista para que alguien la coja, y aquí no la coge nadie, la
     * ejecuta el propio laboratorio. {@code ENVIADA} es {@code in-progress} porque eso es exactamente
     * lo que es: empezada y sin terminar.
     */
    public static TaskStatus aStatus(EstadoDeDeclaracion estado) {
        return switch (estado) {
            case PENDIENTE -> TaskStatus.REQUESTED;
            case ENVIADA -> TaskStatus.INPROGRESS;
            case ACUSADA -> TaskStatus.COMPLETED;
            case RECHAZADA -> TaskStatus.REJECTED;
        };
    }

    /** El nombre en español que publica la guía. Un código a secas deja una pantalla con un hueco. */
    private static String nombreDe(EstadoDeDeclaracion estado) {
        return switch (estado) {
            case PENDIENTE -> "Pendiente de enviar";
            case ENVIADA -> "Enviada, sin acuse";
            case ACUSADA -> "Acusada por Salud Pública";
            case RECHAZADA -> "Rechazada";
        };
    }
}
