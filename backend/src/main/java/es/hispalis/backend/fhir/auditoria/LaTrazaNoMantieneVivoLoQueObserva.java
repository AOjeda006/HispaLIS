package es.hispalis.backend.fhir.auditoria;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.model.DeleteConflictList;
import ca.uhn.fhir.jpa.delete.DeleteConflictOutcome;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Una traza <strong>no mantiene vivo lo que se limitó a observar</strong>.
 *
 * <h2>El problema</h2>
 *
 * <p>HAPI comprueba la integridad referencial también al borrar: un recurso al que apunta otro no se
 * va. Y la traza de acceso referencia <strong>todo lo que alguien ha mirado</strong>, así que el
 * primer {@code AuditEvent} sobre un recurso lo convierte en indestructible — el reconciliador deja
 * de poder retirar un huérfano, que es su trabajo, y el derecho de supresión del RGPD se vuelve
 * imposible de ejercer por culpa del registro que existe justamente para respetarlo.
 *
 * <h2>⚠️ Por qué esto no se arregla con la configuración que parece</h2>
 *
 * <p>{@code JpaStorageSettings.setEnforceReferentialIntegrityOnDeleteDisableForPaths(…)} tiene
 * exactamente ese nombre y <strong>no sirve para un {@code DELETE}</strong>. Medido sobre HAPI
 * 8.10.1, buscando en el bytecode de todos sus JAR quién consulta ese ajuste: lo lee **una sola
 * clase**, {@code ca.uhn.fhir.jpa.delete.batch2.DeleteExpungeSqlBuilder}, que es la del trabajo por
 * lotes {@code $delete-expunge}. El borrado normal pasa por {@code DeleteConflictService}, que no lo
 * mira.
 *
 * <p>Así que el ajuste se pone, no da ningún error, y no hace nada. Estuvo puesto desde el ítem 50
 * y lo destapó un fallo <strong>intermitente</strong> del reconciliador: la traza se escribe después
 * de contestar, así que a veces llegaba antes del borrado y a veces después. Es la cuarta vez en
 * este proyecto que algo se declara bien, no avisa y no funciona ({@code adr-0020},
 * {@code adr-0028}, {@code adr-0029}) — y la defensa vuelve a ser la misma: un test que ejercite el
 * comportamiento, no que compruebe que la configuración está puesta.
 *
 * <h2>La solución</h2>
 *
 * <p>El punto de enganche que sí gobierna un {@code DELETE} normal es
 * {@link Pointcut#STORAGE_PRESTORAGE_DELETE_CONFLICTS}: HAPI entrega la lista de conflictos
 * <strong>antes</strong> de decidir si el borrado se rechaza, y quien la recibe puede quitar de ella
 * lo que no deba estorbar. Aquí se quitan <strong>solo</strong> los caminos por los que una traza
 * apunta a lo que observó. Todo lo demás sigue protegido: borrar un paciente al que apunta un
 * resultado sigue siendo imposible.
 *
 * <p>Lo que queda tras el borrado es la constancia de que alguien lo miró, apuntando a un id que ya
 * no resuelve — que es exactamente lo que hay que conservar.
 */
@Interceptor
@Component
public class LaTrazaNoMantieneVivoLoQueObserva {

    private static final Logger LOG = LoggerFactory.getLogger(LaTrazaNoMantieneVivoLoQueObserva.class);

    /**
     * Los caminos por los que una traza de acceso apunta a un recurso.
     *
     * <p>La lista es explícita y corta a propósito: no se trata de que un {@code AuditEvent} no
     * estorbe nunca, sino de que no estorbe <strong>por lo que observó</strong>. Si algún día la
     * traza gana un elemento que sí deba retener —no se ve cuál—, no estará aquí.
     */
    private static final Set<String> LO_QUE_LA_TRAZA_SOLO_OBSERVA = Set.of(
            "AuditEvent.entity.what",
            "AuditEvent.patient",
            "AuditEvent.agent.who",
            "AuditEvent.source.observer",
            "AuditEvent.basedOn",
            "AuditEvent.encounter");

    /**
     * Quita de la lista de conflictos los que solo son una traza mirando.
     *
     * @param conflictos la lista que HAPI va a evaluar; se modifica en el sitio
     * @return {@code null}: no hay que reintentar nada, solo dejar de contar estos conflictos
     */
    @Hook(Pointcut.STORAGE_PRESTORAGE_DELETE_CONFLICTS)
    public DeleteConflictOutcome noEstorbaLoQueSoloSeMiro(DeleteConflictList conflictos) {
        boolean habia =
                conflictos.removeIf(conflicto -> LO_QUE_LA_TRAZA_SOLO_OBSERVA.contains(conflicto.getSourcePath())
                        && "AuditEvent".equals(conflicto.getSourceId().getResourceType()));
        if (habia) {
            LOG.debug("Se ignoran los conflictos de borrado que solo eran trazas de acceso observando.");
        }
        return null;
    }
}
