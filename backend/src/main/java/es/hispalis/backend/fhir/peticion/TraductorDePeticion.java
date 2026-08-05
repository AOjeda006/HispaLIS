package es.hispalis.backend.fhir.peticion;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import es.hispalis.backend.fhir.Referencias;
import java.util.Date;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations.RequestIntent;
import org.hl7.fhir.r5.model.Enumerations.RequestStatus;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.springframework.stereotype.Component;

/**
 * La frontera entre el {@code ServiceRequest} de FHIR y el agregado {@link Peticion}.
 *
 * <p>⚠️ <strong>R5:</strong> {@code ServiceRequest.code} es un {@code CodeableReference}, no un
 * {@code CodeableConcept} como en R4. Cualquier código copiado de un ejemplo de R4 produce aquí un
 * JSON que no valida, y el error no menciona la versión por ninguna parte.
 */
@Component
public class TraductorDePeticion {

    /** Construye el agregado a partir del recurso recibido, validando de paso sus invariantes. */
    public Peticion aDominio(ServiceRequest recurso) {
        return Peticion.registrar(
                recurso.getRequisition().getValue(),
                Referencias.identidadDe(recurso.getSubject(), "paciente"),
                CatalogoDePruebas.codigoDe(recurso.getCode().getConcept())
                        .orElseThrow(() -> new DatoInvalido(
                                "La petición tiene que decir qué prueba se pide, con un código del catálogo.")),
                referenciaDe(recurso.getRequester()),
                recurso.hasAuthoredOn() ? recurso.getAuthoredOn().toInstant() : null);
    }

    /** Genera el recurso publicable a partir del agregado. Esta es la proyección. */
    public ServiceRequest aFhir(Peticion peticion) {
        ServiceRequest recurso = new ServiceRequest();
        recurso.setId(peticion.id().toString());
        recurso.getMeta().addProfile(PerfilesDeLaGuia.PETICION_LAB.canonica());

        recurso.setStatus(RequestStatus.ACTIVE);
        recurso.setIntent(RequestIntent.ORDER);
        recurso.getRequisition().setValue(peticion.numeroDePeticion());
        recurso.setSubject(new Reference("Patient/" + peticion.pacienteId()));
        recurso.setRequester(new Reference(peticion.solicitante()));
        recurso.setAuthoredOn(Date.from(peticion.solicitadaEn()));

        // `CodeableReference`, no `CodeableConcept`: es la diferencia de R5 que rompe todo lo
        // copiado de R4.
        recurso.setCode(new CodeableReference(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(peticion.codigoDePrueba()))));
        return recurso;
    }

    private static String referenciaDe(Reference referencia) {
        if (referencia == null || referencia.getReferenceElement().isEmpty()) {
            return null;
        }
        return referencia.getReference();
    }
}
