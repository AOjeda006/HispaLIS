package es.hispalis.backend.fhir.notificacion;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Una notificación anotada: a quién hay que avisar, de qué recurso, y cómo ha ido.
 *
 * <p><strong>Lleva la referencia del recurso, nunca el recurso.</strong> Que el contenido no esté
 * aquí es lo que convierte {@code content = id-only} en una garantía y no en una opción de
 * serialización: aunque alguien cambiara el constructor del cuerpo, no habría de dónde sacar el
 * valor de la TSH.
 *
 * @param id identidad de la fila
 * @param suscripcionId id lógico de la {@code Subscription} a la que hay que entregarla
 * @param numero {@code eventNumber}, correlativo por suscripción desde 1
 * @param foco referencia al recurso ({@code Observation/…})
 * @param ocurridoEn cuándo pasó el hecho, no cuándo se entregó
 * @param estado en qué punto está
 * @param intentos cuántas veces se ha intentado entregar
 * @param ultimoError el motivo técnico del último fallo, si lo hubo
 */
public record EventoDeNotificacion(
        UUID id,
        String suscripcionId,
        long numero,
        String foco,
        Instant ocurridoEn,
        EstadoDeLaEntrega estado,
        int intentos,
        String ultimoError) {

    /** En qué punto está la entrega de una notificación. */
    public enum EstadoDeLaEntrega {
        /** Escrita y todavía sin salir. */
        PENDIENTE,
        /** El receptor la aceptó. */
        ENTREGADO,
        /**
         * Se agotaron los intentos. Es el estado que deja la suscripción en {@code error}: a partir
         * de aquí no se vuelve a intentar sola, y reactivarla es un acto explícito de alguien.
         */
        FALLIDO
    }

    /** Una notificación recién anotada, todavía sin salir. */
    public static EventoDeNotificacion pendiente(String suscripcionId, long numero, String foco, Instant ocurridoEn) {
        return new EventoDeNotificacion(
                UUID.randomUUID(), suscripcionId, numero, foco, ocurridoEn, EstadoDeLaEntrega.PENDIENTE, 0, null);
    }

    public Optional<String> motivoDelFallo() {
        return Optional.ofNullable(ultimoError);
    }
}
