package es.hispalis.backend.fhir.informe;

import es.hispalis.backend.dominio.informe.Informe;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import java.util.Date;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.DiagnosticReport.DiagnosticReportStatus;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.stereotype.Component;

/** La frontera entre el {@code DiagnosticReport} de FHIR y el agregado {@link Informe}. */
@Component
public class TraductorDeInforme {

    private static final String LOINC = "http://loinc.org";

    /**
     * Código LOINC del informe de laboratorio.
     *
     * <p>Va sin {@code display} a propósito: un {@code Coding} de terminología externa lleva
     * {@code system} y {@code code}, y el término lo resuelve el servidor de terminología. Fijarlo
     * aquí hace que el recurso valide o no según el idioma de quien lo valide (ADR-0009).
     */
    private static final String INFORME_DE_LABORATORIO = "11502-2";

    /** Genera el recurso publicable a partir del agregado. Esta es la proyección. */
    public DiagnosticReport aFhir(Informe informe) {
        DiagnosticReport recurso = new DiagnosticReport();
        recurso.setId(informe.id().toString());
        recurso.getMeta().addProfile(PerfilesDeLaGuia.INFORME_LAB.canonica());

        recurso.setStatus(DiagnosticReportStatus.FINAL);
        recurso.setCode(
                new CodeableConcept().addCoding(new Coding().setSystem(LOINC).setCode(INFORME_DE_LABORATORIO)));
        recurso.setSubject(new Reference("Patient/" + informe.pacienteId()));
        recurso.addPerformer(new Reference(informe.emisor()));
        recurso.setIssued(Date.from(informe.emitidoEn()));

        informe.resultadoIds().forEach(resultado -> recurso.addResult(new Reference("Observation/" + resultado)));
        return recurso;
    }
}
