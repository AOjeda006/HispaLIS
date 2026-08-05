package es.hispalis.backend.fhir.especimen;

import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.EstadoDeEspecimen;
import es.hispalis.backend.dominio.especimen.NumeroDeAcceso;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import es.hispalis.backend.fhir.Referencias;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Specimen;
import org.hl7.fhir.r5.model.Specimen.SpecimenStatus;
import org.springframework.stereotype.Component;

/** La frontera entre el {@code Specimen} de FHIR y el agregado {@link Especimen}. */
@Component
public class TraductorDeEspecimen {

    private static final String SNOMED = "http://snomed.info/sct";

    /** Construye el agregado a partir del recurso recibido, validando de paso sus invariantes. */
    public Especimen aDominio(Specimen recurso) {
        return Especimen.registrar(
                new NumeroDeAcceso(recurso.getAccessionIdentifier().getValue()),
                Referencias.identidadDe(recurso.getSubject(), "paciente"),
                recurso.getType().getCodingFirstRep().getCode(),
                estadoDe(recurso),
                motivoDeRechazoDe(recurso));
    }

    /** Genera el recurso publicable a partir del agregado. Esta es la proyección. */
    public Specimen aFhir(Especimen especimen) {
        Specimen recurso = new Specimen();
        recurso.setId(especimen.id().toString());
        recurso.getMeta().addProfile(PerfilesDeLaGuia.ESPECIMEN_LAB.canonica());

        recurso.getAccessionIdentifier().setValue(especimen.numeroDeAcceso().valor());
        recurso.setSubject(new Reference("Patient/" + especimen.pacienteId()));
        recurso.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(especimen.tipo())));
        recurso.setStatus(estadoFhirDe(especimen.estado()));
        especimen.motivoDeRechazo().ifPresent(motivo -> recurso.addCondition(new CodeableConcept().setText(motivo)));
        return recurso;
    }

    /**
     * Extrae el motivo del rechazo como texto legible.
     *
     * <p>Se guarda el texto y no el código porque el dominio no es un servidor de terminología: lo
     * que necesita saber es <em>por qué</em> se rechazó, para poder decírselo a quien pregunte. La
     * codificación vive en la proyección, que es donde se consume.
     */
    private static String motivoDeRechazoDe(Specimen recurso) {
        if (recurso.getCondition().isEmpty()) {
            return null;
        }
        CodeableConcept condicion = recurso.getCondition().get(0);
        if (condicion.hasText()) {
            return condicion.getText();
        }
        Coding codigo = condicion.getCodingFirstRep();
        return codigo.hasDisplay() ? codigo.getDisplay() : codigo.getCode();
    }

    private static EstadoDeEspecimen estadoDe(Specimen recurso) {
        if (recurso.getStatus() == null) {
            // `Specimen.status` es 0..1 en FHIR. Una muestra que llega sin estado está disponible:
            // es lo que significa que alguien la haya registrado.
            return EstadoDeEspecimen.DISPONIBLE;
        }
        return switch (recurso.getStatus()) {
            case AVAILABLE -> EstadoDeEspecimen.DISPONIBLE;
            case UNAVAILABLE -> EstadoDeEspecimen.NO_DISPONIBLE;
            case UNSATISFACTORY -> EstadoDeEspecimen.RECHAZADA;
            case ENTEREDINERROR -> EstadoDeEspecimen.ERROR_DE_REGISTRO;
            default -> EstadoDeEspecimen.DISPONIBLE;
        };
    }

    private static SpecimenStatus estadoFhirDe(EstadoDeEspecimen estado) {
        return switch (estado) {
            case DISPONIBLE -> SpecimenStatus.AVAILABLE;
            case NO_DISPONIBLE -> SpecimenStatus.UNAVAILABLE;
            case RECHAZADA -> SpecimenStatus.UNSATISFACTORY;
            case ERROR_DE_REGISTRO -> SpecimenStatus.ENTEREDINERROR;
        };
    }
}
