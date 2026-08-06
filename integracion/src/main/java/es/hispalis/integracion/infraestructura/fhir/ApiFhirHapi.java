package es.hispalis.integracion.infraestructura.fhir;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.SearchStyleEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import es.hispalis.integracion.destino.ApiFhirDelLaboratorio;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import java.util.Optional;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Component;

/**
 * El destino, hablado con el cliente FHIR de HAPI.
 *
 * <p>Es un cliente HTTP normal contra la API pública del laboratorio, y esa normalidad es la
 * decisión (D5): el motor no tiene atajos. Si un canal necesitara algo que la API no ofrece, lo que
 * falta es una operación en el laboratorio.
 */
@Component
public class ApiFhirHapi implements ApiFhirDelLaboratorio {

    private final IGenericClient cliente;

    public ApiFhirHapi(IGenericClient cliente) {
        this.cliente = cliente;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Va por {@code POST …/_search} y no por la URL. El NHC identifica a una persona, y una URL
     * con él dentro acaba en el log de acceso del servidor, en el del proxy y en el historial de
     * cualquier intermediario — sitios donde nadie decidió que hubiera datos de paciente (ADR-0016).
     */
    @Override
    public Optional<String> buscarPacientePorNhc(String nhc) {
        Bundle encontrados = cliente.search()
                .forResource(Patient.class)
                .where(new TokenClientParam("identifier").exactly().systemAndCode(SistemasDeIdentificador.NHC, nhc))
                .usingStyle(SearchStyleEnum.POST)
                .returnBundle(Bundle.class)
                .execute();

        return encontrados.getEntry().stream()
                .filter(Bundle.BundleEntryComponent::hasResource)
                .map(entrada -> entrada.getResource().getIdElement().toUnqualifiedVersionless())
                .map(id -> "Patient/" + id.getIdPart())
                .findFirst();
    }

    @Override
    public String darDeAltaPaciente(Patient paciente) {
        try {
            MethodOutcome creado =
                    cliente.create().resource(paciente).prettyPrint().execute();
            return "Patient/" + creado.getId().getIdPart();
        } catch (BaseServerResponseException rechazo) {
            throw traducir("dar de alta al paciente", rechazo);
        }
    }

    @Override
    public String corregirPaciente(String referencia, Patient paciente) {
        try {
            Patient conIdentidad = paciente.copy();
            conIdentidad.setId(referencia);
            cliente.update().resource(conIdentidad).execute();
            return referencia;
        } catch (BaseServerResponseException rechazo) {
            throw traducir("corregir la filiación", rechazo);
        }
    }

    /**
     * Convierte el error de la API en algo que el operador del HIS pueda usar.
     *
     * <p>Se saca el texto del {@code OperationOutcome}, que es donde el laboratorio explica qué está
     * mal. Un «error 422 al escribir» no le dice a nadie qué corregir; «el número de historia clínica
     * son exactamente ocho dígitos», sí.
     */
    private static ElLaboratorioRechaza traducir(String queSeIntentaba, BaseServerResponseException rechazo) {
        String detalle = rechazo.getOperationOutcome() instanceof OperationOutcome outcome
                        && outcome.hasIssue()
                        && outcome.getIssueFirstRep().hasDiagnostics()
                ? outcome.getIssueFirstRep().getDiagnostics()
                : rechazo.getMessage();
        return new ElLaboratorioRechaza(
                "El laboratorio rechazó %s (HTTP %d): %s".formatted(queSeIntentaba, rechazo.getStatusCode(), detalle),
                rechazo);
    }
}
