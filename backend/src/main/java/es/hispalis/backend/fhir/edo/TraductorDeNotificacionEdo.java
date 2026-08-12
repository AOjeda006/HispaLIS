package es.hispalis.backend.fhir.edo;

import es.hispalis.backend.dominio.edo.ModalidadDeDeclaracion;
import es.hispalis.backend.dominio.edo.NotificacionEdo;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import java.util.Date;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations.RequestPriority;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Period;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Task;
import org.springframework.stereotype.Component;

/**
 * La declaración a Salud Pública, publicada como {@code Task}.
 *
 * <p>Va en un {@code Task} y no en una extensión porque §6.1 ya lo verificó: una notificación tiene
 * ciclo de vida propio —se abre, sale, se acusa, puede rechazarse— y eso es exactamente lo que el
 * recurso modela. Una extensión sobre el {@code Observation} diría «este resultado es declarable», que
 * es otra cosa y ya la dice el catálogo.
 *
 * <p><strong>El id es el del agregado</strong>, no uno derivado como el de las procedencias. La
 * diferencia está en quién tiene identidad: una firma no es una entidad del dominio y su
 * {@code Provenance} necesitaba una identidad calculada para que el reconciliador no duplicase; una
 * declaración sí lo es, y ya trae la suya.
 *
 * <p><strong>Aquí no hay filiación.</strong> No es que se filtre: el agregado no la tiene. Lo que
 * viaja al recurso son referencias y códigos.
 */
@Component
public class TraductorDeNotificacionEdo {

    private static final String TIPOS_DE_TAREA = "http://hl7.org/fhir/CodeSystem/task-code";
    private static final String ENFERMEDADES = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/enfermedades-edo";

    /**
     * @param declaracion el agregado, en el estado en que esté
     * @param destinatario a quién se declara, como referencia al organismo de Salud Pública
     */
    public Task aFhir(NotificacionEdo declaracion, String destinatario) {
        Task tarea = new Task();
        tarea.setId(declaracion.id().toString());
        tarea.getMeta().addProfile(PerfilesDeLaGuia.NOTIFICACION_EDO.canonica());

        tarea.setStatus(EstadosDeDeclaracion.aStatus(declaracion.estado()));
        tarea.setIntent(Task.TaskIntent.ORDER);
        tarea.setBusinessStatus(EstadosDeDeclaracion.aBusinessStatus(declaracion.estado()));
        tarea.setPriority(prioridadDe(declaracion.modalidad()));

        // El `code` dice qué clase de tarea es; el texto, de qué enfermedad. La enfermedad va también
        // codificada en `reason`, que es donde se busca: `code.text` no se indexa.
        tarea.setCode(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem(TIPOS_DE_TAREA)
                        .setCode("fulfill")
                        .setDisplay("Fulfill the focal request"))
                .setText("Declaración de enfermedad de declaración obligatoria"));
        tarea.getReason()
                .add(new org.hl7.fhir.r5.model.CodeableReference(new CodeableConcept()
                        .addCoding(new Coding()
                                .setSystem(ENFERMEDADES)
                                .setCode(declaracion.codigoDeEnfermedad())
                                .setDisplay(declaracion.nombreDeLaEnfermedad()))
                        .setText(declaracion.nombreDeLaEnfermedad())));

        tarea.setFocus(new Reference("Observation/" + declaracion.resultadoId()));
        tarea.setFor(new Reference("Patient/" + declaracion.pacienteId()));
        // El declarante es el centro que emitió el resultado. Se omite si no se sabe, en vez de
        // apuntar a un valor de relleno: un `requester` inventado diría que declaró alguien que no fue.
        declaracion.declarante().ifPresent(centro -> tarea.setRequester(new Reference(centro)));
        tarea.setOwner(new Reference(destinatario));

        tarea.setAuthoredOn(Date.from(declaracion.abiertaEn()));
        tarea.setLastModified(Date.from(java.time.Instant.now()));

        // EL PLAZO. Solo el final del intervalo: el principio es cuándo nació la obligación y eso ya
        // está en `authoredOn`. Repetirlo daría dos sitios que se pueden contradecir.
        tarea.getRestriction().setPeriod(new Period().setEnd(Date.from(declaracion.vencimiento())));

        // EL ACUSE, que es lo único que acredita que la declaración está hecha. `Identifier` y no
        // cadena: el número es de Salud Pública y el `system` dice de quién es.
        declaracion.acuse().ifPresent(recibo -> tarea.addOutput()
                .setType(new CodeableConcept().setText("Número de registro de la declaración en Salud Pública"))
                .setValue(new Identifier().setSystem(recibo.sistema()).setValue(recibo.numero())));

        // El motivo del último intento fallido, en la nota. Es técnico —«Connection refused», «422 del
        // destinatario»—, nunca clínico: de la persona no hay nada que contar aquí.
        declaracion.ultimoError().ifPresent(motivo -> tarea.addNote().setText(motivo));

        return tarea;
    }

    /**
     * Urgente → {@code stat}, ordinaria → {@code routine}.
     *
     * <p>En el elemento estándar y no en una extensión (§6.1). Es lo que permite ordenar una bandeja de
     * declaraciones sin consultar el catálogo por cada fila.
     */
    private static RequestPriority prioridadDe(ModalidadDeDeclaracion modalidad) {
        return modalidad == ModalidadDeDeclaracion.URGENTE ? RequestPriority.STAT : RequestPriority.ROUTINE;
    }
}
