package es.hispalis.backend.fhir.notificacion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Puerto de la bandeja de notificaciones: lo que hay que entregar y lo que ya se entregó. */
public interface BandejaDeNotificaciones {

    /**
     * Anota una notificación, <strong>en la transacción de quien la provoca</strong>.
     *
     * <p>El número de evento lo asigna la propia bandeja porque es correlativo por suscripción y
     * dejárselo a quien llama sería repartir esa cuenta por todo el código.
     *
     * @return la notificación anotada, con su número
     */
    EventoDeNotificacion anotar(String suscripcionId, String foco, Instant ocurridoEn);

    /** Lo pendiente de entregar, lo más antiguo primero. */
    List<EventoDeNotificacion> pendientes(int tanda);

    /** Lo de una suscripción, por número de evento, para {@code $events}. */
    List<EventoDeNotificacion> deLaSuscripcion(String suscripcionId, long desde, long hasta);

    /** Cuántos hechos han ocurrido desde que la suscripción empezó. Es {@code eventsSinceSubscriptionStart}. */
    long eventosDe(String suscripcionId);

    /** El último fallo de esa suscripción, si lo hay. Es lo que sale por {@code $status}. */
    List<EventoDeNotificacion> fallidosDe(String suscripcionId);

    void marcarEntregado(UUID id, Instant cuando);

    /**
     * Apunta un intento fallido.
     *
     * @param definitivo si ya no se va a reintentar; entonces la fila queda {@code FALLIDO} y la
     *     suscripción se corta
     */
    void marcarIntentoFallido(UUID id, String motivo, boolean definitivo);
}
