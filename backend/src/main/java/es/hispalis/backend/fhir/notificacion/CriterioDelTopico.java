package es.hispalis.backend.fhir.notificacion;

import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.SubscriptionTopic;
import org.hl7.fhir.r5.model.SubscriptionTopic.SubscriptionTopicResourceTriggerComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Evalúa el disparador de un {@code SubscriptionTopic} contra una escritura concreta.
 *
 * <p>⚠️ <strong>Esto es lo que en R4 era {@code Subscription.criteria}.</strong> Allí cada suscriptor
 * escribía su propia cadena de búsqueda dentro de su recurso; aquí el criterio lo publica el servidor
 * en un recurso de conformidad y todas las suscripciones lo comparten. La consecuencia de código es
 * la que se ve en esta clase: <strong>no hay ni una condición escrita en Java</strong>. Lo que se
 * compara sale del {@code SubscriptionTopic}, y cambiarlo es cambiar el recurso.
 *
 * <p>Las cadenas de {@code queryCriteria} son criterios de búsqueda FHIR y se evalúan
 * <strong>en memoria</strong> con el emparejador de HAPI, el mismo que usa su propio motor de
 * suscripciones. No se ejecutan contra la base de datos y no pueden: la versión <em>anterior</em> del
 * recurso no está indexada como tal.
 *
 * <p><strong>Un criterio que el emparejador no sepa evaluar no dispara y se avisa.</strong> Es lo
 * único honesto: darlo por bueno notificaría por algo que nadie ha comprobado, y darlo por falso en
 * silencio dejaría un tópico que no funciona con toda la pinta de funcionar.
 */
@Component
public class CriterioDelTopico {

    private static final Logger LOG = LoggerFactory.getLogger(CriterioDelTopico.class);

    /** Prefijo del tipo de recurso en {@code resourceTrigger.resource}, según la propia definición de R5. */
    private static final String BASE_DE_TIPOS = "http://hl7.org/fhir/StructureDefinition/";

    private final SearchParamMatcher emparejador;

    public CriterioDelTopico(SearchParamMatcher emparejador) {
        this.emparejador = emparejador;
    }

    /**
     * Si esta escritura dispara el tópico.
     *
     * @param topico el tópico publicado, tal cual está en el almacén
     * @param anterior la versión previa del recurso, o {@code null} si es un alta
     * @param actual el recurso tal y como queda
     */
    public boolean dispara(SubscriptionTopic topico, IBaseResource anterior, IBaseResource actual) {
        return topico.getResourceTrigger().stream().anyMatch(gatillo -> dispara(gatillo, anterior, actual));
    }

    private boolean dispara(
            SubscriptionTopicResourceTriggerComponent gatillo, IBaseResource anterior, IBaseResource actual) {
        String tipo = tipoDe(gatillo.getResource());
        if (!actual.fhirType().equals(tipo)) {
            return false;
        }
        if (!admiteLaInteraccion(gatillo, anterior == null)) {
            return false;
        }
        if (!gatillo.hasQueryCriteria()) {
            // Un gatillo sin criterio dispara con cualquier escritura de ese tipo. Es válido en R5 y
            // aquí no lo usa nadie, pero tratarlo como «no dispara» sería silenciar un tópico legítimo.
            return true;
        }

        boolean pasaElActual = casa(gatillo.getQueryCriteria().getCurrent(), tipo, actual, true);
        boolean pasaElAnterior = anterior == null
                ? seCuentaComoAprobadoEnUnAlta(gatillo)
                : casa(gatillo.getQueryCriteria().getPrevious(), tipo, anterior, true);

        return gatillo.getQueryCriteria().getRequireBoth()
                ? pasaElActual && pasaElAnterior
                : pasaElActual || pasaElAnterior;
    }

    /**
     * En un alta no hay estado anterior contra el que preguntar, y el tópico tiene que decir qué se
     * hace con eso: {@code resultForCreate} es exactamente ese elemento. Sin declararlo, R5 deja la
     * decisión «a discreción del servidor», y aquí la discreción es no disparar — notificar por algo
     * que el tópico no ha dicho es peor que no notificar.
     */
    private static boolean seCuentaComoAprobadoEnUnAlta(SubscriptionTopicResourceTriggerComponent gatillo) {
        return gatillo.getQueryCriteria().hasResultForCreate()
                && "test-passes"
                        .equals(gatillo.getQueryCriteria().getResultForCreate().toCode());
    }

    private static boolean admiteLaInteraccion(SubscriptionTopicResourceTriggerComponent gatillo, boolean esUnAlta) {
        if (gatillo.getSupportedInteraction().isEmpty()) {
            return true;
        }
        String interaccion = esUnAlta ? "create" : "update";
        return gatillo.getSupportedInteraction().stream().anyMatch(admitida -> interaccion.equals(admitida.getCode()));
    }

    /**
     * Evalúa un criterio de búsqueda contra un recurso concreto.
     *
     * <p>El criterio del tópico va sin el tipo delante —{@code status=final}—, tal y como manda R5
     * («the rules are search criteria without the [base] part»), y el emparejador de HAPI lo quiere
     * con él. Ponerlo aquí y no en el recurso es lo correcto: el recurso está bien como está.
     *
     * @param siFalta qué contestar cuando el tópico no declara ese criterio; un criterio ausente no
     *     estorba, así que no estrecha la condición
     */
    private boolean casa(String criterio, String tipo, IBaseResource recurso, boolean siFalta) {
        if (criterio == null || criterio.isBlank()) {
            return siFalta;
        }
        InMemoryMatchResult resultado = emparejador.match(tipo + "?" + criterio, recurso, null);
        if (!resultado.supported()) {
            LOG.warn(
                    "El criterio «{}» del tópico no se puede evaluar en memoria, así que NO se notifica por él: {}",
                    criterio,
                    resultado.getUnsupportedReason());
            return false;
        }
        return resultado.matched();
    }

    /** {@code resourceTrigger.resource} es la canónica del {@code StructureDefinition}, o el tipo a secas. */
    private static String tipoDe(String recurso) {
        if (recurso == null) {
            return "";
        }
        return recurso.startsWith(BASE_DE_TIPOS) ? recurso.substring(BASE_DE_TIPOS.length()) : recurso;
    }
}
