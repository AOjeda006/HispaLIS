package es.hispalis.backend.fhir.notificacion;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import java.time.Instant;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Enumerations.SubscriptionStatusCodes;
import org.hl7.fhir.r5.model.Subscription;
import org.hl7.fhir.r5.model.SubscriptionTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Anota qué hay que notificar, <strong>dentro de la transacción que lo provoca</strong>.
 *
 * <p>Es el mismo patrón que el {@code outbox} de Kafka y por las mismas dos razones: una notificación
 * de un resultado cuya transacción acabó revirtiendo sería una mentira que no se puede retirar, y una
 * notificación perdida porque el receptor estaba caído es un aviso clínico que no llega. Escribir la
 * fila aquí resuelve las dos; entregarla es cosa del relay, ya fuera de la transacción — una llamada
 * HTTP a un tercero dentro de una transacción de base de datos la mantendría abierta lo que tarde en
 * responder alguien que no controlamos.
 *
 * <p><strong>Qué decide qué se notifica:</strong> el {@code SubscriptionTopic}, no esta clase. Aquí
 * no hay ni un {@code if} sobre el estado del recurso — se le pregunta a {@link CriterioDelTopico},
 * que evalúa lo que el tópico publicado dice.
 *
 * <p>Se engancha en {@code STORAGE_PRECOMMIT_*} y no en el registro del {@code RestfulServer}: los
 * puntos {@code STORAGE_*} los dispara la capa JPA, y es la única forma de ver también las escrituras
 * que no vienen de una petición REST — la proyección la escriben los casos de uso llamando a las DAO.
 */
@Interceptor
@Component
public class AnotarLasNotificaciones {

    private static final Logger LOG = LoggerFactory.getLogger(AnotarLasNotificaciones.class);

    private final TopicosDelLaboratorio topicos;
    private final CriterioDelTopico criterio;
    private final BandejaDeNotificaciones bandeja;
    private final DaoRegistry daos;

    public AnotarLasNotificaciones(
            TopicosDelLaboratorio topicos,
            CriterioDelTopico criterio,
            BandejaDeNotificaciones bandeja,
            DaoRegistry daos) {
        this.topicos = topicos;
        this.criterio = criterio;
        this.bandeja = bandeja;
        this.daos = daos;
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void alCrear(IBaseResource creado) {
        anotarLoQueDispare(null, creado);
    }

    /**
     * @param anterior la versión previa; es lo que permite distinguir «está en {@code final}» de
     *     «acaba de pasar a {@code final}», que es la diferencia entre notificar una vez y notificar
     *     en cada reescritura posterior del mismo recurso
     */
    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void alActualizar(IBaseResource anterior, IBaseResource actual) {
        anotarLoQueDispare(anterior, actual);
    }

    private void anotarLoQueDispare(IBaseResource anterior, IBaseResource actual) {
        // Un `SubscriptionTopic` o una `Subscription` que se escriben no disparan nada, y preguntarlo
        // provocaría además una lectura del almacén dentro de su propia escritura. Lo que sí hace un
        // tópico escrito es invalidar lo que había cacheado: es cómo corregir un tópico surte efecto
        // sin reiniciar.
        if (actual == null || esDeLaPropiaMaquinaria(actual)) {
            if (actual != null && "SubscriptionTopic".equals(actual.fhirType())) {
                topicos.olvidar();
            }
            return;
        }
        for (SubscriptionTopic topico : topicos.publicados()) {
            if (!criterio.dispara(topico, anterior, actual)) {
                continue;
            }
            String foco = actual.getIdElement().toUnqualifiedVersionless().getValue();
            for (Subscription suscripcion : activasDe(topico.getUrl())) {
                EventoDeNotificacion anotada =
                        bandeja.anotar(suscripcion.getIdElement().getIdPart(), foco, Instant.now());
                LOG.debug(
                        "Notificación {} anotada para {} sobre {}",
                        anotada.numero(),
                        anotada.suscripcionId(),
                        anotada.foco());
            }
        }
    }

    private static boolean esDeLaPropiaMaquinaria(IBaseResource recurso) {
        String tipo = recurso.fhirType();
        return "Subscription".equals(tipo) || "SubscriptionTopic".equals(tipo) || "SubscriptionStatus".equals(tipo);
    }

    /**
     * Las suscripciones vivas a ese tópico.
     *
     * <p>Se busca por estado y se filtra el tópico en memoria a propósito: son unas pocas filas, y
     * atarse al índice del parámetro {@code topic} haría que un servidor con ese {@code
     * SearchParameter} desactivado dejara de notificar <strong>sin dar ningún error</strong>.
     *
     * <p>{@code error} no entra, y ese es el corte: una suscripción cuya entrega falló definitivamente
     * deja de acumular trabajo hasta que alguien la reactive. Sin esto, un receptor apagado un fin de
     * semana se encuentra el lunes con miles de notificaciones y el laboratorio se habría pasado dos
     * días llamando a una puerta cerrada.
     */
    private List<Subscription> activasDe(String canonicaDelTopico) {
        SearchParameterMap busqueda = SearchParameterMap.newSynchronous()
                .add(Subscription.SP_STATUS, new TokenParam(SubscriptionStatusCodes.ACTIVE.toCode()));

        return daos
                .getResourceDao(Subscription.class)
                .search(busqueda, new SystemRequestDetails())
                .getAllResources()
                .stream()
                .map(Subscription.class::cast)
                .filter(suscripcion -> canonicaDelTopico.equals(suscripcion.getTopic()))
                .toList();
    }
}
