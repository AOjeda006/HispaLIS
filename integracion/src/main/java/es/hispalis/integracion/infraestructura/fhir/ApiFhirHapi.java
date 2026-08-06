package es.hispalis.integracion.infraestructura.fhir;

import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.SearchStyleEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import es.hispalis.integracion.destino.ApiFhirDelLaboratorio;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;
import org.springframework.stereotype.Component;

/**
 * El destino, hablado con el cliente FHIR de HAPI.
 *
 * <p>Es un cliente HTTP normal contra la API pública del laboratorio, y esa normalidad es la
 * decisión (D5): el motor no tiene atajos. Si un canal necesitara algo que la API no ofrece, lo que
 * falta es una operación en el laboratorio.
 *
 * <p><strong>Todas las búsquedas van por {@code POST …/_search}</strong>, no solo la del paciente.
 * El criterio de una de ellas es un NHC y el de otras un número de volante; la regla se aplica a
 * todas por la misma razón por la que se aplica a una: una URL con criterios de búsqueda dentro
 * acaba en el log de acceso del servidor, en el del proxy y en el historial de cualquier
 * intermediario, y allí nadie decidió que hubiera datos de un episodio asistencial (ADR-0016).
 */
@Component
public class ApiFhirHapi implements ApiFhirDelLaboratorio {

    private final IGenericClient cliente;

    public ApiFhirHapi(IGenericClient cliente) {
        this.cliente = cliente;
    }

    @Override
    public Optional<String> buscarPacientePorNhc(String nhc) {
        return primeraReferencia(
                "Patient",
                cliente.search()
                        .forResource(Patient.class)
                        .where(new TokenClientParam("identifier")
                                .exactly()
                                .systemAndCode(SistemasDeIdentificador.NHC, nhc)));
    }

    @Override
    public String darDeAltaPaciente(Patient paciente) {
        return crear("dar de alta al paciente", "Patient", paciente);
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
     * {@inheritDoc}
     *
     * <p>Se busca por {@code requisition} y se filtra la prueba <strong>aquí</strong> en vez de
     * añadir un segundo criterio a la consulta. Un volante tiene tres o cuatro líneas: traerlas todas
     * y mirar el código cuesta lo mismo, y no depende de que el servidor indexe
     * {@code ServiceRequest.code.concept}, que en R5 es un {@code CodeableReference} y no todos los
     * servidores lo resuelven igual.
     */
    @Override
    public Optional<String> buscarLinea(String numeroDeVolante, String codigoDePrueba) {
        Bundle encontradas = ejecutar(cliente.search()
                .forResource(ServiceRequest.class)
                .where(new TokenClientParam("requisition").exactly().code(numeroDeVolante)));

        return recursos(encontradas, ServiceRequest.class).stream()
                .filter(linea -> codigoDePrueba.equals(codigoLocalDe(linea)))
                .map(linea -> "ServiceRequest/" + linea.getIdElement().getIdPart())
                .findFirst();
    }

    @Override
    public String registrarLinea(ServiceRequest linea) {
        return crear("registrar la línea de petición", "ServiceRequest", linea);
    }

    @Override
    public Optional<String> buscarEspecimen(String numeroDeAcceso) {
        return primeraReferencia(
                "Specimen",
                cliente.search()
                        .forResource(Specimen.class)
                        .where(new TokenClientParam("accession").exactly().code(numeroDeAcceso)));
    }

    @Override
    public String registrarEspecimen(Specimen especimen) {
        return crear("registrar la muestra", "Specimen", especimen);
    }

    @Override
    public Optional<String> buscarResultado(String especimenRef, String codigoDePrueba) {
        Bundle encontrados = ejecutar(cliente.search()
                .forResource(Observation.class)
                .where(new TokenClientParam("specimen").exactly().code(especimenRef)));

        return recursos(encontrados, Observation.class).stream()
                .filter(resultado -> codigoDePrueba.equals(codigoLocalDe(resultado.getCode())))
                .map(resultado -> "Observation/" + resultado.getIdElement().getIdPart())
                .findFirst();
    }

    @Override
    public String informarResultado(Observation resultado) {
        return crear("informar el resultado", "Observation", resultado);
    }

    @Override
    public DiagnosticReport leerInforme(String referencia) {
        try {
            return cliente.read()
                    .resource(DiagnosticReport.class)
                    .withId(idDe(referencia))
                    .execute();
        } catch (BaseServerResponseException rechazo) {
            throw traducir("leer el informe " + referencia, rechazo);
        }
    }

    @Override
    public List<Observation> leerResultados(List<String> referencias) {
        List<Observation> resultados = new ArrayList<>(referencias.size());
        for (String referencia : referencias) {
            try {
                resultados.add(cliente.read()
                        .resource(Observation.class)
                        .withId(idDe(referencia))
                        .execute());
            } catch (BaseServerResponseException rechazo) {
                throw traducir("leer el resultado " + referencia, rechazo);
            }
        }
        return resultados;
    }

    @Override
    public Patient leerPaciente(String referencia) {
        try {
            return cliente.read()
                    .resource(Patient.class)
                    .withId(idDe(referencia))
                    .execute();
        } catch (BaseServerResponseException rechazo) {
            throw traducir("leer el paciente " + referencia, rechazo);
        }
    }

    private String crear(String queSeIntentaba, String tipo, Resource recurso) {
        try {
            MethodOutcome creado = cliente.create().resource(recurso).execute();
            return tipo + "/" + creado.getId().getIdPart();
        } catch (BaseServerResponseException rechazo) {
            throw traducir(queSeIntentaba, rechazo);
        }
    }

    private Optional<String> primeraReferencia(String tipo, IQuery<?> consulta) {
        return recursos(ejecutar(consulta), Resource.class).stream()
                .map(recurso -> tipo + "/" + recurso.getIdElement().getIdPart())
                .findFirst();
    }

    /**
     * Ejecuta la búsqueda pidiendo <strong>expresamente</strong> que no se sirva de caché.
     *
     * <p>Todas las búsquedas de este cliente son de idempotencia: se busca para no volver a escribir
     * lo que ya está. Un resultado de hace un minuto no sirve para eso, y el servidor FHIR de HAPI
     * reutiliza por omisión el de los últimos sesenta segundos. El laboratorio ya lo lleva apagado
     * —es un invariante suyo, no una cortesía—, pero el motor no puede permitirse suponerlo: si
     * alguna vez habla con un servidor que sí cachea, el reproceso duplicaría en silencio.
     */
    private static Bundle ejecutar(IQuery<?> consulta) {
        return (Bundle) consulta.usingStyle(SearchStyleEnum.POST)
                .cacheControl(new CacheControlDirective().setNoCache(true))
                .returnBundle(Bundle.class)
                .execute();
    }

    private static <T extends Resource> List<T> recursos(Bundle bundle, Class<T> tipo) {
        return bundle.getEntry().stream()
                .filter(Bundle.BundleEntryComponent::hasResource)
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(tipo::isInstance)
                .map(tipo::cast)
                .toList();
    }

    /** {@code ServiceRequest.code} es un {@code CodeableReference} en R5, no un concepto a secas. */
    private static String codigoLocalDe(ServiceRequest linea) {
        return linea.getCode().hasConcept() ? codigoLocalDe(linea.getCode().getConcept()) : null;
    }

    private static String codigoLocalDe(org.hl7.fhir.r5.model.CodeableConcept concepto) {
        return concepto.getCoding().stream()
                .filter(codigo -> CatalogoDelLaboratorio.SYSTEM.equals(codigo.getSystem()))
                .map(org.hl7.fhir.r5.model.Coding::getCode)
                .findFirst()
                .orElse(null);
    }

    /** De {@code Tipo/<id>} a {@code <id>}; el cliente ya sabe el tipo por el {@code read()}. */
    private static String idDe(String referencia) {
        int barra = referencia.lastIndexOf('/');
        return barra < 0 ? referencia : referencia.substring(barra + 1);
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
