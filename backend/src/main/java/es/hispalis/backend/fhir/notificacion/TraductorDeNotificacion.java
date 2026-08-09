package es.hispalis.backend.fhir.notificacion;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Bundle.BundleType;
import org.hl7.fhir.r5.model.Bundle.HTTPVerb;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Subscription;
import org.hl7.fhir.r5.model.SubscriptionStatus;
import org.hl7.fhir.r5.model.SubscriptionStatus.SubscriptionNotificationType;

/**
 * Arma lo que viaja por el canal: el {@code Bundle} de tipo {@code subscription-notification}.
 *
 * <h2>Lo que NO lleva</h2>
 *
 * <p>El recurso. Con {@code content = id-only} —el único que este laboratorio acepta— las entradas
 * llevan {@code fullUrl} y una petición {@code GET}, y ahí se acaba. Ni el valor de la determinación,
 * ni el nombre del paciente, ni su número de historia. Quien reciba la notificación va a buscar el
 * recurso a la API con su testigo, y allí se le aplica el consentimiento del paciente como a
 * cualquier otra lectura; un {@code full-resource} se saltaría las dos cosas y estaría mandando
 * historia clínica por un canal saliente a un sistema que no la ha pedido (invariante 6).
 *
 * <p>Que aquí no haya de dónde sacar el contenido no es casualidad: {@link EventoDeNotificacion} solo
 * guarda la referencia, así que ni cambiando esta clase se podría publicar el valor.
 *
 * <h2>La primera entrada</h2>
 *
 * <p>Es obligatoria y tiene que ser un {@code SubscriptionStatus} —lo exige la invariante {@code
 * bdl-13} del propio R5—, y es lo que da sentido al resto: de qué suscripción es esto, de qué tópico
 * y qué número de evento. El número es lo que permite al receptor darse cuenta de que se ha perdido
 * el 7 sin tener que preguntar nada.
 */
public final class TraductorDeNotificacion {

    /** Los tres códigos con los que R5 deja contar por qué falló una entrega. */
    private static final String CODIGO_DE_ERROR = "http://terminology.hl7.org/CodeSystem/subscription-error";

    private TraductorDeNotificacion() {}

    /**
     * La notificación de uno o varios hechos.
     *
     * @param baseFhir la base pública de esta API, para componer los {@code fullUrl}
     * @param eventosDesdeElPrincipio cuántos hechos han ocurrido en total; NO se reinicia al fallar,
     *     porque cuenta lo ocurrido y no lo entregado — es lo que deja ver cuántos se perdieron
     */
    public static Bundle notificacion(
            Subscription suscripcion,
            List<EventoDeNotificacion> eventos,
            long eventosDesdeElPrincipio,
            String baseFhir) {
        SubscriptionStatus estado =
                estado(suscripcion, eventos, eventosDesdeElPrincipio, SubscriptionNotificationType.EVENTNOTIFICATION);

        Bundle notificacion = new Bundle();
        notificacion.setType(BundleType.SUBSCRIPTIONNOTIFICATION);
        notificacion.setTimestamp(new Date());
        notificacion.addEntry().setFullUrl("urn:uuid:" + UUID.randomUUID()).setResource(estado);

        if (!llevaIdentidades(suscripcion)) {
            return notificacion;
        }
        for (EventoDeNotificacion evento : eventos) {
            // `fullUrl` y `request`, sin `resource`: la invariante `bdl-5` de R5 exige que una entrada
            // sin recurso traiga petición, y la petición dice exactamente lo que el receptor tiene
            // que hacer para verlo — ir a buscarlo con su testigo.
            Bundle.BundleEntryComponent entrada = notificacion.addEntry();
            entrada.setFullUrl(baseFhir + "/" + evento.foco());
            entrada.getRequest().setMethod(HTTPVerb.GET).setUrl(evento.foco());
        }
        return notificacion;
    }

    /** La respuesta de {@code $status}: el mismo recurso, sin cuerpo de notificación alrededor. */
    public static SubscriptionStatus estado(
            Subscription suscripcion,
            List<EventoDeNotificacion> eventos,
            long eventosDesdeElPrincipio,
            SubscriptionNotificationType tipo) {
        SubscriptionStatus estado = new SubscriptionStatus();
        estado.setStatus(suscripcion.getStatus());
        estado.setType(tipo);
        estado.setEventsSinceSubscriptionStart(eventosDesdeElPrincipio);
        estado.setSubscription(
                new Reference("Subscription/" + suscripcion.getIdElement().getIdPart()));
        estado.setTopic(suscripcion.getTopic());

        for (EventoDeNotificacion evento : eventos) {
            estado.addNotificationEvent()
                    .setEventNumber(evento.numero())
                    .setTimestamp(Date.from(evento.ocurridoEn()))
                    .setFocus(new Reference(evento.foco()));
        }
        return estado;
    }

    /**
     * El motivo del fallo, donde R5 lo puso.
     *
     * <p>⚠️ R4 tenía {@code Subscription.error}, una cadena dentro del propio recurso.
     * <strong>En R5 ese elemento no existe</strong>: el estado sigue siendo {@code error}, pero el
     * motivo va aquí, codificado. Buscarlo en {@code Subscription} y no encontrarlo lleva derecho a
     * inventarse una extensión para algo que el estándar ya modela.
     */
    public static CodeableConcept motivoDelFallo(String codigo, String detalle) {
        return new CodeableConcept()
                .addCoding(new Coding().setSystem(CODIGO_DE_ERROR).setCode(codigo))
                .setText(detalle);
    }

    /**
     * Si las entradas llevan la identidad del recurso o el cuerpo va vacío.
     *
     * <p>{@code full-resource} se rechaza al escribir la {@code Subscription}, así que aquí no
     * debería llegar nunca. Si llegara —una fila escrita antes de esa comprobación, una migración—
     * se trata como {@code id-only}: degradar a menos información es seguro, al revés no.
     */
    private static boolean llevaIdentidades(Subscription suscripcion) {
        return suscripcion.getContent() != Subscription.SubscriptionPayloadContent.EMPTY;
    }
}
