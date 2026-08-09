package es.hispalis.backend.fhir.notificacion;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Enumerations.SubscriptionStatusCodes;
import org.hl7.fhir.r5.model.Subscription;
import org.hl7.fhir.r5.model.Subscription.SubscriptionPayloadContent;
import org.hl7.fhir.r5.model.SubscriptionTopic;
import org.springframework.stereotype.Component;

/**
 * La puerta de las suscripciones: qué se le deja pedir a un cliente y qué no.
 *
 * <h2>{@code full-resource} no</h2>
 *
 * <p>Es la comprobación que sostiene el invariante 6 en el canal saliente. Una notificación con el
 * recurso dentro manda la historia clínica a un sistema que <strong>no la ha pedido en esa
 * petición</strong>, sin testigo por delante y sin que se le aplique el consentimiento del paciente
 * — y basta con que alguien escriba una palabra distinta en el recurso para que pase. Se cierra al
 * escribir y no al entregar: dejar la {@code Subscription} guardada diciendo {@code full-resource}
 * y luego no honrarlo sería mentirle al suscriptor sobre lo que va a recibir.
 *
 * <h2>Un tópico que no existe, tampoco</h2>
 *
 * <p>En R5 el criterio vive en el {@code SubscriptionTopic}, así que una suscripción a un tópico que
 * este servidor no publica no es un error del futuro: es una suscripción que <strong>nunca va a
 * recibir nada</strong>, y aceptarla en silencio deja al suscriptor esperando un aviso que no llega.
 * Es exactamente el fallo que R4 no podía detectar, porque allí el criterio era una cadena libre.
 */
@Interceptor
@Component
public class SuscripcionesQueElLaboratorioAcepta {

    private final TopicosDelLaboratorio topicos;

    public SuscripcionesQueElLaboratorioAcepta(TopicosDelLaboratorio topicos) {
        this.topicos = topicos;
    }

    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
    public void alCrear(IBaseResource recurso) {
        comprobar(recurso);
    }

    @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
    public void alActualizar(IBaseResource anterior, IBaseResource actual) {
        comprobar(actual);
    }

    private void comprobar(IBaseResource recurso) {
        if (!(recurso instanceof Subscription suscripcion)) {
            return;
        }
        if (suscripcion.getContent() == SubscriptionPayloadContent.FULLRESOURCE) {
            throw new ReglaDeNegocioIncumplida(
                    "Este laboratorio no entrega notificaciones con el recurso dentro (`full-resource`): eso sería "
                            + "mandar historia clínica por un canal saliente, sin testigo y sin consentimiento "
                            + "aplicado. Pide `id-only` y resuelve la referencia contra la API.");
        }
        if (!suscripcion.hasEndpoint() && suscripcion.getStatus() == SubscriptionStatusCodes.ACTIVE) {
            throw new ReglaDeNegocioIncumplida(
                    "Una suscripción activa sin `endpoint` no se puede entregar en ninguna parte.");
        }

        List<String> publicados =
                topicos.publicados().stream().map(SubscriptionTopic::getUrl).toList();
        if (!publicados.contains(suscripcion.getTopic())) {
            throw new ReglaDeNegocioIncumplida(
                    ("Este laboratorio no publica el tópico «%s», así que una suscripción a él no recibiría nada "
                                    + "nunca. Los que sí publica: %s. En R5 el criterio vive en el "
                                    + "`SubscriptionTopic`, no en la `Subscription`.")
                            .formatted(
                                    suscripcion.getTopic(),
                                    publicados.isEmpty() ? "ninguno todavía" : String.join(", ", publicados)));
        }
    }
}
