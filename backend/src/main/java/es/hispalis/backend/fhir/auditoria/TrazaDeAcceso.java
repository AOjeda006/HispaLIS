package es.hispalis.backend.fhir.auditoria;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.util.ResourceReferenceInfo;
import es.hispalis.backend.fhir.auditoria.Acceso.Desenlace;
import es.hispalis.backend.fhir.auditoria.Acceso.Recurso;
import es.hispalis.backend.fhir.seguridad.QuienLlama;
import es.hispalis.backend.fhir.seguridad.Testigo;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.AuditEvent.AuditEventAction;
import org.hl7.fhir.r5.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deja constancia de cada acceso a la API: quién, qué, cuándo y desde dónde.
 *
 * <h2>Se escribe DESPUÉS de contestar, y es una decisión</h2>
 *
 * <p>La alternativa —no contestar hasta que la traza esté grabada— es defendible en abstracto y es
 * la que pediría un manual de seguridad. Aquí no se toma, y por el mismo motivo por el que el
 * notificador EDO no bloquea la validación de un resultado (ítem 48): un laboratorio que dejara de
 * entregar un resultado crítico porque la tabla de auditoría no admite escrituras sería un fallo peor
 * que el que se intenta evitar. Un fallo al registrar sale por el log como error, no se traga en
 * silencio.
 *
 * <h2>Por qué hacen falta tres puntos de enganche</h2>
 *
 * <ul>
 *   <li>{@code SERVER_OUTGOING_RESPONSE} es el único sitio donde se ve <strong>qué recursos salieron
 *       de verdad</strong>. La petición no lo sabe: en una búsqueda son los del {@code Bundle}, y en
 *       un alta es el id que acaba de asignarse.
 *   <li>{@code SERVER_HANDLE_EXCEPTION} es donde se ve el desenlace de lo que <strong>no</strong>
 *       salió. Sin él, el registro solo tendría los accesos correctos — que es justo lo que no se
 *       investiga después de un incidente.
 *   <li>{@code SERVER_PROCESSING_COMPLETED} cierra siempre, haya ido bien o mal, y es donde se
 *       escribe.
 * </ul>
 *
 * <p>Va en el registro del {@code RestfulServer} y no en el del almacenamiento: los tres son puntos
 * {@code SERVER_*}, que dispara el propio servidor REST. Registrarlo en el otro no daría error — no se
 * llamaría nunca.
 *
 * <p><strong>Escribe con {@code SystemRequestDetails}</strong>, que es lo que dice «esto lo hace el
 * servidor, no un cliente». Sin eso, la traza se escribiría con los permisos de quien llamó y un
 * testigo de solo lectura no dejaría rastro — es decir, justo el que más interesa registrar sería el
 * único que no se registra.
 */
@Interceptor
public class TrazaDeAcceso {

    private static final Logger LOG = LoggerFactory.getLogger(TrazaDeAcceso.class);

    /** Clave con la que se guarda lo recogido de la respuesta hasta que toca escribir. */
    private static final String LO_QUE_SALIO = TrazaDeAcceso.class.getName() + ".recursos";

    private static final String COMO_ACABO = TrazaDeAcceso.class.getName() + ".desenlace";

    /**
     * Tope de entidades por traza.
     *
     * <p>Una página de búsqueda trae hasta doscientos recursos, y una traza con doscientas referencias
     * pesa más que la propia respuesta. Se registra el acceso y una muestra de lo devuelto; quién
     * buscó, cuándo y con qué desenlace es lo que hace falta para investigar, y eso no se recorta.
     */
    private static final int MAXIMO_DE_ENTIDADES = 50;

    private final QuienLlama quienLlama;
    private final TraductorDeTraza traductor;
    private final DaoRegistry daos;
    private final FhirContext contexto;

    public TrazaDeAcceso(QuienLlama quienLlama, TraductorDeTraza traductor, DaoRegistry daos, FhirContext contexto) {
        this.quienLlama = quienLlama;
        this.traductor = traductor;
        this.daos = daos;
        this.contexto = contexto;
    }

    /** Qué recursos salieron de verdad. Es lo único que la petición por sí sola no puede decir. */
    @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
    public boolean anotarLoQueSale(RequestDetails peticion, ResponseDetails respuesta) {
        if (peticion != null && respuesta != null && respuesta.getResponseResource() != null) {
            peticion.getUserData().put(LO_QUE_SALIO, referenciasDe(respuesta.getResponseResource()));
        }
        return true;
    }

    /** Y cómo acabó lo que no salió. */
    @Hook(Pointcut.SERVER_HANDLE_EXCEPTION)
    public boolean anotarElFallo(RequestDetails peticion, BaseServerResponseException fallo) {
        if (peticion != null && fallo != null) {
            peticion.getUserData().put(COMO_ACABO, Desenlace.deHttp(fallo.getStatusCode()));
        }
        return true;
    }

    /** Y aquí se levanta acta, salga como salga. */
    @Hook(Pointcut.SERVER_PROCESSING_COMPLETED)
    public void levantarActa(RequestDetails peticion) {
        if (peticion == null || !seAudita(peticion)) {
            return;
        }
        try {
            escribir(traductor.aFhir(loQuePaso(peticion)));
        } catch (RuntimeException noSePudoRegistrar) {
            // Un acceso sin traza es un problema, y sale como error. Lo que no puede es tumbar una
            // respuesta que el cliente ya tiene.
            LOG.error(
                    "No se ha podido registrar la traza de acceso a {}. El acceso SÍ ocurrió.",
                    peticion.getResourceName(),
                    noSePudoRegistrar);
        }
    }

    private void escribir(AuditEvent traza) {
        daos.getResourceDao(AuditEvent.class).create(traza, new SystemRequestDetails());
    }

    private Acceso loQuePaso(RequestDetails peticion) {
        Optional<Testigo> testigo = quienLlama.testigo();
        RestOperationTypeEnum tipo = peticion.getRestOperationType();
        List<Recurso> recursos = loAccedido(peticion);

        return new Acceso(
                interaccionDe(tipo),
                accionDe(tipo),
                Instant.now(),
                testigo.map(Testigo::sujeto),
                testigo.flatMap(Testigo::fhirUser),
                direccionDe(peticion),
                recursos,
                deQuienEra(recursos),
                (Desenlace) peticion.getUserData().getOrDefault(COMO_ACABO, Desenlace.CORRECTO));
    }

    /**
     * Lo accedido: lo que salió por la respuesta y, si no estaba ahí, el recurso que la URL nombraba.
     *
     * <p>Las dos fuentes hacen falta. Un {@code DELETE} no devuelve recurso y su id solo está en la
     * petición; una búsqueda no lleva id en la petición y sus resultados solo están en la respuesta.
     *
     * <p>Y se distinguen, porque de eso depende cómo se escribe la referencia: lo que salió existe, lo
     * que solo se pidió puede no existir. Ver {@code TraductorDeTraza.referenciaA}.
     */
    @SuppressWarnings("unchecked")
    private static List<Recurso> loAccedido(RequestDetails peticion) {
        Set<String> devueltos = (Set<String>) peticion.getUserData().getOrDefault(LO_QUE_SALIO, Set.of());

        Set<Recurso> recursos = new LinkedHashSet<>();
        devueltos.forEach(referencia -> recursos.add(new Recurso(referencia, true)));

        if (peticion.getId() != null
                && peticion.getId().hasResourceType()
                && peticion.getId().hasIdPart()) {
            String pedido = peticion.getId().toUnqualifiedVersionless().getValue();
            if (!devueltos.contains(pedido)) {
                recursos.add(new Recurso(pedido, false));
            }
        }
        return recursos.stream().limit(MAXIMO_DE_ENTIDADES).toList();
    }

    /**
     * De quién eran los datos, si de lo accedido se puede deducir una persona.
     *
     * <p>Solo de lo que el servidor llegó a devolver: {@code AuditEvent.patient} es una referencia
     * literal, y apuntarla a un paciente que no existe deja la traza sin poder escribirse.
     */
    private static Optional<String> deQuienEra(List<Recurso> recursos) {
        return recursos.stream()
                .filter(recurso -> recurso.devuelto() && recurso.esDe("Patient"))
                .map(Recurso::referencia)
                .findFirst();
    }

    /**
     * Las referencias de lo que sale, incluidas las de dentro de un {@code Bundle} de búsqueda.
     *
     * <p>Se toma <strong>solo el identificador</strong> de cada recurso. Nada del contenido llega a la
     * traza: ni un nombre, ni un valor, ni el {@code display} de una referencia.
     */
    private Set<String> referenciasDe(IBaseResource recurso) {
        Set<String> referencias = new LinkedHashSet<>();

        if (recurso instanceof Bundle bundle) {
            bundle.getEntry().stream()
                    .map(Bundle.BundleEntryComponent::getResource)
                    .filter(java.util.Objects::nonNull)
                    .forEach(dentro -> anadir(referencias, dentro));
            return referencias;
        }

        anadir(referencias, recurso);
        // Y de quién era: la referencia al paciente que el propio recurso trae. No se copia el
        // `Reference` —arrastraría su `display`, que es el nombre—, solo su `Tipo/id`.
        contexto.newTerser().getAllResourceReferences(recurso).stream()
                .map(ResourceReferenceInfo::getResourceReference)
                .map(referencia -> referencia.getReferenceElement().toUnqualifiedVersionless())
                .filter(identidad -> "Patient".equals(identidad.getResourceType()) && identidad.hasIdPart())
                .map(org.hl7.fhir.instance.model.api.IIdType::getValue)
                .forEach(referencias::add);

        return referencias;
    }

    private static void anadir(Set<String> referencias, IBaseResource recurso) {
        if (recurso.getIdElement() != null
                && recurso.getIdElement().hasResourceType()
                && recurso.getIdElement().hasIdPart()) {
            referencias.add(recurso.getIdElement().toUnqualifiedVersionless().getValue());
        }
    }

    private static Optional<String> direccionDe(RequestDetails peticion) {
        return peticion instanceof ServletRequestDetails servlet && servlet.getServletRequest() != null
                ? Optional.ofNullable(servlet.getServletRequest().getRemoteAddr())
                : Optional.empty();
    }

    /**
     * Qué se audita.
     *
     * <p>{@code metadata} y el descubrimiento quedan fuera: son públicos por diseño y no tocan datos
     * de nadie. Registrarlos llenaría el registro de ruido y haría más difícil encontrar lo que sí
     * importa, que es el efecto contrario al que se busca.
     */
    private static boolean seAudita(RequestDetails peticion) {
        RestOperationTypeEnum tipo = peticion.getRestOperationType();
        return tipo != null && tipo != RestOperationTypeEnum.METADATA;
    }

    /** La interacción REST en el vocabulario del estándar. Una operación es {@code operation}. */
    private static String interaccionDe(RestOperationTypeEnum tipo) {
        return switch (tipo) {
            case EXTENDED_OPERATION_SERVER, EXTENDED_OPERATION_TYPE, EXTENDED_OPERATION_INSTANCE -> "operation";
            // Pedir la página siguiente es seguir buscando, y en el vocabulario de interacciones no
            // hay un código propio para ello.
            case GET_PAGE -> "search-type";
            default -> tipo.getCode();
        };
    }

    /**
     * {@code C} | {@code R} | {@code U} | {@code D} | {@code E}.
     *
     * <p>Es lo que permite listar «todas las escrituras de ayer» sin saberse de memoria los nombres de
     * las interacciones, y por eso está además del código.
     */
    private static AuditEventAction accionDe(RestOperationTypeEnum tipo) {
        return switch (tipo) {
            case CREATE -> AuditEventAction.C;
            case UPDATE, UPDATE_REWRITE_HISTORY, PATCH -> AuditEventAction.U;
            case DELETE -> AuditEventAction.D;
            case READ, VREAD, HISTORY_INSTANCE, HISTORY_TYPE, HISTORY_SYSTEM -> AuditEventAction.R;
            default -> AuditEventAction.E;
        };
    }
}
