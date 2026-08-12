package es.hispalis.backend.aplicacion.exportacion;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import es.hispalis.backend.dominio.edo.NotificacionEdo;
import es.hispalis.backend.fhir.exportacion.TraductorDeCohorte;
import java.util.Date;
import org.hl7.fhir.r5.model.Group;
import org.hl7.fhir.r5.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mete el caso declarado en la cohorte de su enfermedad.
 *
 * <p>Es el eslabón entre el ítem 48 y Bulk Data, y también lo que le da a la exportación un motivo:
 * <strong>la cohorte no la compone nadie, se forma sola al declarar</strong>. Un cliente que pudiera
 * componerla exportaría a quien quisiera, y entonces el «motivo legal real» del diseño (§4.4) sería un
 * adorno.
 *
 * <p>Se ejecuta en la misma transacción que abre la declaración: si la declaración existe, el caso
 * está en la cohorte. Lo contrario dejaría una ventana en la que el laboratorio ha declarado una
 * legionelosis y su cohorte todavía no la cuenta — que es justo el momento en el que alguien
 * investigando un brote la pediría.
 *
 * <p><strong>Es idempotente por miembro.</strong> Un paciente con dos legionelosis en un año es un
 * caso de la cohorte, no dos: duplicarlo inflaría el recuento igual que lo inflaría una declaración
 * repetida.
 */
@Service
public class ApuntarEnLaCohorte {

    private static final Logger LOG = LoggerFactory.getLogger(ApuntarEnLaCohorte.class);

    private final DaoRegistry daos;
    private final TraductorDeCohorte traductor;

    public ApuntarEnLaCohorte(DaoRegistry daos, TraductorDeCohorte traductor) {
        this.daos = daos;
        this.traductor = traductor;
    }

    @Transactional
    public void ejecutar(NotificacionEdo declaracion) {
        Group cohorte = laDe(declaracion);
        String paciente = "Patient/" + declaracion.pacienteId();

        boolean yaEstaba = cohorte.getMember().stream()
                .anyMatch(miembro -> paciente.equals(miembro.getEntity().getReference()));
        if (yaEstaba) {
            return;
        }

        cohorte.addMember().setEntity(new Reference(paciente)).getPeriod().setStart(Date.from(declaracion.abiertaEn()));

        // Quién responde de la cohorte: el centro que declaró. Se pone una vez y no se pisa — quien la
        // abrió es de lo que hay que responder, y el `performer` de un resultado se puede corregir.
        if (!cohorte.hasManagingEntity()) {
            declaracion.declarante().ifPresent(centro -> cohorte.setManagingEntity(new Reference(centro)));
        }
        cohorte.setQuantity(cohorte.getMember().size());

        daos.getResourceDao(Group.class).update(cohorte, new SystemRequestDetails());
        LOG.info(
                "Cohorte de vigilancia {}: un caso más, {} en total.",
                declaracion.codigoDeEnfermedad(),
                cohorte.getMember().size());
    }

    /**
     * La cohorte de esa enfermedad, creándola si es la primera vez.
     *
     * <p>El id es calculable a partir del código, así que no hace falta descubrirla: o está o hay que
     * abrirla. Es lo que permite que dos declaraciones de la misma enfermedad a la vez acaben en el
     * mismo recurso en vez de en dos cohortes gemelas.
     *
     * <p>⚠️ <strong>Se BUSCA en vez de leer-y-cazar-la-excepción, y esa línea costó una tarde.</strong>
     * {@code IFhirResourceDao.read} es {@code @Transactional}, así que cuando el recurso no existe y
     * lanza {@code ResourceNotFoundException}, Spring marca <em>toda</em> la transacción como
     * <em>rollback-only</em> — y <strong>capturar la excepción no lo deshace</strong>. El código
     * seguía adelante tan tranquilo, la cohorte se creaba, el log decía «un caso más, 1 en total» y al
     * confirmar saltaba un {@code UnexpectedRollbackException} que tiraba la vuelta entera del
     * notificador: ni declaración, ni cohorte, ni nada. La causa no aparece por ningún sitio cerca del
     * síntoma. Una búsqueda devuelve el conjunto vacío en vez de lanzar, y no ensucia la transacción.
     */
    private Group laDe(NotificacionEdo declaracion) {
        String id = TraductorDeCohorte.idDeLaCohorte(declaracion.codigoDeEnfermedad());
        SearchParameterMap porId = SearchParameterMap.newSynchronous().add("_id", new TokenParam(id));

        return daos.getResourceDao(Group.class).search(porId, new SystemRequestDetails()).getAllResources().stream()
                .map(Group.class::cast)
                .findFirst()
                .orElseGet(() -> traductor.nueva(declaracion.codigoDeEnfermedad(), declaracion.nombreDeLaEnfermedad()));
    }
}
