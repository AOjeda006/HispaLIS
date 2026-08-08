package es.hispalis.backend.fhir.seguridad;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.interceptor.consent.ConsentOutcome;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentContextServices;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentService;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.rest.server.util.ResourceSearchParams;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

/**
 * De quién son los datos que pueden salir: el consentimiento, aplicado <strong>en el servidor
 * FHIR</strong>.
 *
 * <p>Esta es la mitad que no se puede delegar. El servidor de identidad concede <em>scopes</em> y el
 * proxy enruta; ninguno de los dos sabe de quién es una {@code Observation}, y ponerlo en cualquiera
 * de ellos exigiría que empezaran a hablar FHIR — que es cómo se acaba con dos servidores FHIR y
 * ninguno conforme. Aquí se sabe, porque aquí está el recurso.
 *
 * <p><strong>Un scope concedido no garantiza los datos.</strong> Un testigo con {@code patient/*.rs}
 * ha pedido y obtenido permiso de lectura sobre <em>todos los tipos de recurso</em>, y
 * {@link AutorizacionSmart} se lo concede: eso es lo que dice el <em>scope</em>. Lo que el
 * <em>scope</em> no dice —y no puede decir— es de quién. Eso lo dice el contexto de lanzamiento que
 * viaja firmado en el testigo, y lo aplica este servicio, recurso a recurso, antes de que ninguno
 * salga por el cable.
 *
 * <p><strong>Dos formas de decir que no, y la diferencia importa.</strong> A una lectura directa
 * —«dame {@code Patient/B}»— se le responde {@code 403}: el cliente pidió algo concreto y merece
 * saber que no puede tenerlo. A una búsqueda se le <em>omite</em> el recurso sin más: contestar «hay
 * tres que no te enseño» ya sería contar algo de quien no lo autorizó, y con un par de búsquedas bien
 * elegidas se reconstruye lo que se quería ocultar.
 *
 * <p>El compartimento no se adivina: se pregunta al registro de parámetros de búsqueda cuáles dan
 * pertenencia al compartimento {@code Patient}, que es la definición que publica el propio FHIR.
 * Escribir aquí «mira {@code subject} y {@code patient}» funcionaría hoy y dejaría de funcionar el
 * día que aparezca un recurso que se une al paciente por otro elemento.
 */
public class ConsentimientoDelPaciente implements IConsentService {

    private static final String COMPARTIMENTO = "Patient";

    private final QuienLlama quienLlama;
    private final FhirContext contexto;
    private final ISearchParamRegistry parametrosDeBusqueda;

    public ConsentimientoDelPaciente(
            QuienLlama quienLlama, FhirContext contexto, ISearchParamRegistry parametrosDeBusqueda) {
        this.quienLlama = quienLlama;
        this.contexto = contexto;
        this.parametrosDeBusqueda = parametrosDeBusqueda;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sin contexto de paciente no hay consentimiento que aplicar y se sale por
     * {@code AUTHORIZED}. No es un atajo de rendimiento —aunque también lo sea, y de los buenos: se
     * ahorra recorrer cada recurso de cada respuesta del laboratorio—: es que el consentimiento de un
     * paciente no puede decir nada sobre una consulta que no va de un paciente.
     */
    @Override
    public ConsentOutcome startOperation(RequestDetails peticion, IConsentContextServices servicios) {
        return quienLlama.testigo().filter(Testigo::limitadoAUnPaciente).isPresent()
                ? ConsentOutcome.PROCEED
                : ConsentOutcome.AUTHORIZED;
    }

    @Override
    public ConsentOutcome canSeeResource(
            RequestDetails peticion, IBaseResource recurso, IConsentContextServices servicios) {
        Optional<String> enContexto =
                quienLlama.testigo().flatMap(Testigo::pacienteEnContexto).filter(id -> !id.isBlank());

        // Un testigo `patient/` sin paciente en el contexto está mal emitido. La lectura amable
        // —«sin restricción, luego todo»— es exactamente el fallo que convierte un error de
        // configuración del servidor de identidad en una fuga de datos.
        if (enContexto.isEmpty()) {
            throw new ForbiddenOperationException(
                    "El testigo pide datos en nombre de un paciente pero no dice de cuál: sin contexto de "
                            + "lanzamiento no se puede saber qué le corresponde ver.");
        }

        if (pacientesDe(recurso).contains(enContexto.get())) {
            return ConsentOutcome.PROCEED;
        }
        if (esLecturaDirecta(peticion)) {
            throw new ForbiddenOperationException(
                    "Este recurso no pertenece al paciente sobre el que se ha lanzado la aplicación.");
        }
        return ConsentOutcome.REJECT;
    }

    /**
     * A qué pacientes pertenece el recurso.
     *
     * <p>Devuelve un conjunto y no un valor porque hay recursos que pertenecen a más de uno; y
     * devuelve el conjunto <strong>vacío</strong> cuando no se puede determinar, que es la respuesta
     * segura: lo que no se sabe de quién es, no sale.
     */
    private Set<String> pacientesDe(IBaseResource recurso) {
        String tipo = contexto.getResourceType(recurso);
        if (COMPARTIMENTO.equals(tipo)) {
            return Set.of(recurso.getIdElement().getIdPart());
        }

        Set<String> pacientes = new HashSet<>();
        ResourceSearchParams parametros =
                parametrosDeBusqueda.getActiveSearchParams(tipo, ISearchParamRegistry.SearchParamLookupContextEnum.ALL);
        if (parametros == null) {
            return pacientes;
        }
        for (RuntimeSearchParam parametro : parametros.values()) {
            if (!parametro.getProvidesMembershipInCompartments().contains(COMPARTIMENTO)) {
                continue;
            }
            for (String ruta : parametro.getPathsSplitForResourceType(tipo)) {
                recogerPacientes(recurso, ruta, pacientes);
            }
        }
        return pacientes;
    }

    private void recogerPacientes(IBaseResource recurso, String ruta, Set<String> pacientes) {
        for (IBase valor : contexto.newTerser().getValues(recurso, ruta)) {
            if (valor instanceof IBaseReference referencia) {
                IIdType id = referencia.getReferenceElement();
                if (COMPARTIMENTO.equals(id.getResourceType()) && id.hasIdPart()) {
                    pacientes.add(id.getIdPart());
                }
            }
        }
    }

    private static boolean esLecturaDirecta(RequestDetails peticion) {
        RestOperationTypeEnum operacion = peticion.getRestOperationType();
        return operacion == RestOperationTypeEnum.READ || operacion == RestOperationTypeEnum.VREAD;
    }
}
