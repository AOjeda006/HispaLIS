package es.hispalis.backend.fhir.peticion;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import es.hispalis.backend.fhir.Referencias;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.util.Date;
import org.hl7.fhir.r5.model.Annotation;
import org.hl7.fhir.r5.model.CodeableReference;
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

    private final Terminologia terminologia;

    public TraductorDePeticion(Terminologia terminologia) {
        this.terminologia = terminologia;
    }

    /** Construye el agregado a partir del recurso recibido, validando de paso sus invariantes. */
    public Peticion aDominio(ServiceRequest recurso) {
        String codigo = CatalogoDePruebas.codigoDe(recurso.getCode().getConcept())
                .orElseThrow(() -> new DatoInvalido(
                        "La petición tiene que decir qué prueba se pide, con un código del catálogo."));
        // Lo pregunta el servidor de terminología, no una lista de aquí: el catálogo crece cuando
        // el laboratorio incorpora una técnica, y eso pasa en la guía, no en este fichero.
        terminologia.exigirQueLaPruebaExiste(codigo);

        return Peticion.registrar(
                recurso.getRequisition().getValue(),
                Referencias.identidadDe(recurso.getSubject(), "paciente"),
                codigo,
                referenciaDe(recurso.getRequester()),
                recurso.hasAuthoredOn() ? recurso.getAuthoredOn().toInstant() : null);
    }

    /** Genera el recurso publicable a partir del agregado. Esta es la proyección. */
    public ServiceRequest aFhir(Peticion peticion) {
        ServiceRequest recurso = new ServiceRequest();
        recurso.setId(peticion.id().toString());
        recurso.getMeta().addProfile(PerfilesDeLaGuia.PETICION_LAB.canonica());

        // R5 no tiene `statusReason` en `ServiceRequest` —es de `Task` y `MedicationRequest`—, así
        // que el motivo de la anulación va en `note`, que es elemento estándar y está en el perfil.
        // Publicar el `revoked` a secas obligaría al peticionario a llamar por teléfono.
        recurso.setStatus(peticion.estaAnulada() ? RequestStatus.REVOKED : RequestStatus.ACTIVE);
        peticion.motivoDeAnulacion().ifPresent(motivo -> recurso.addNote(new Annotation().setText(motivo)));

        // `reflex-order` en vez de `order` cuando la añadió el laboratorio. No es cosmético: es la
        // diferencia entre «esto lo pidió el clínico» y «esto lo añadió el laboratorio siguiendo su
        // protocolo», y quien reciba el recurso la necesita para saber a quién preguntarle por qué.
        // FHIR ya lo modela en `intent`, así que no hace falta extensión ninguna.
        recurso.setIntent(peticion.disparadaPor().isPresent() ? RequestIntent.REFLEXORDER : RequestIntent.ORDER);
        peticion.motivoDelDisparo().ifPresent(motivo -> recurso.addNote(new Annotation().setText(motivo)));
        recurso.getRequisition().setValue(peticion.numeroDePeticion());
        recurso.setSubject(new Reference("Patient/" + peticion.pacienteId()));
        recurso.setRequester(new Reference(peticion.solicitante()));
        recurso.setAuthoredOn(Date.from(peticion.solicitadaEn()));

        // `CodeableReference`, no `CodeableConcept`: es la diferencia de R5 que rompe todo lo
        // copiado de R4. El concepto de dentro lo arma la terminología —nombre en español y LOINC
        // equivalente—, no este fichero.
        recurso.setCode(new CodeableReference(terminologia.pruebaDelCatalogo(peticion.codigoDePrueba())));
        return recurso;
    }

    private static String referenciaDe(Reference referencia) {
        if (referencia == null || referencia.getReferenceElement().isEmpty()) {
            return null;
        }
        return referencia.getReference();
    }
}
