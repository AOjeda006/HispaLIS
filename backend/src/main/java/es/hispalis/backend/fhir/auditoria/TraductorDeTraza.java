package es.hispalis.backend.fhir.auditoria;

import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import java.util.Date;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Reference;

/**
 * De un acceso a su {@code AuditEvent}.
 *
 * <p><strong>Lo que este traductor no sabe hacer es tan importante como lo que hace:</strong> no
 * recibe la petición, así que no puede copiar el criterio de búsqueda ni el cuerpo. Lo que entra es
 * {@link Acceso}, que solo tiene referencias y códigos.
 *
 * <p>⚠️ <strong>R5 no es R4 en este recurso, y cambia mucho.</strong> {@code category}/{@code code}
 * sustituyen a {@code type}/{@code subtype} y son {@code CodeableConcept} y no {@code Coding};
 * {@code outcome} pasó de código suelto a elemento con su {@code Coding} dentro; y
 * {@code agent.network[x]} sustituye al {@code agent.network} con {@code address} y {@code type} de
 * R4. Un {@code AuditEvent} de R4 copiado aquí <strong>no valida</strong>.
 */
public class TraductorDeTraza {

    /** El sistema con el que se identifica al cliente cuando no hay un recurso FHIR que lo represente. */
    public static final String SISTEMA_CLIENTE = "https://aojeda006.github.io/HispaLIS/sid/cliente";

    /** Y el del propio servidor, que es quien levanta acta. */
    public static final String SISTEMA_SERVIDOR = "https://aojeda006.github.io/HispaLIS/sid/servidor";

    /** Con el que se nombra un recurso que se pidió y que puede no existir. Ver {@code referenciaA}. */
    public static final String SISTEMA_RECURSO_PEDIDO = "https://aojeda006.github.io/HispaLIS/sid/recurso-pedido";

    /** Y con el que se nombra el {@code fhirUser} del testigo. Ver {@code quienLlamo}. */
    public static final String SISTEMA_USUARIO_DEL_TESTIGO =
            "https://aojeda006.github.io/HispaLIS/sid/usuario-del-testigo";

    /** Con qué se identifica a quien llamó sin testigo. Vale la pena que se vea, no que se omita. */
    public static final String SIN_IDENTIFICAR = "sin-identificar";

    private static final String TIPOS_DE_TRAZA = "http://terminology.hl7.org/CodeSystem/audit-event-type";
    private static final String INTERACCION_REST = "http://hl7.org/fhir/restful-interaction";
    private static final String DESENLACES = "http://terminology.hl7.org/CodeSystem/audit-event-outcome";
    private static final String PAPEL_DE_AGENTE = "http://terminology.hl7.org/CodeSystem/extra-security-role-type";
    private static final String PAPEL_DE_OBJETO = "http://terminology.hl7.org/CodeSystem/object-role";

    private final String observador;

    /**
     * @param observador cómo se llama este servidor en las trazas que escribe. Sin él, una traza
     *     recogida de varios sistemas en un mismo SIEM no se puede atribuir a ninguno
     */
    public TraductorDeTraza(String observador) {
        this.observador = observador;
    }

    public AuditEvent aFhir(Acceso acceso) {
        AuditEvent traza = new AuditEvent();
        traza.getMeta().addProfile(PerfilesDeLaGuia.TRAZA_DE_ACCESO.canonica());

        traza.addCategory(codigo(TIPOS_DE_TRAZA, "rest", "RESTful Operation"));
        traza.setCode(codigo(INTERACCION_REST, acceso.interaccion(), acceso.interaccion()));
        traza.setAction(acceso.accion());
        traza.setSeverity(
                acceso.desenlace() == Acceso.Desenlace.CORRECTO
                        ? AuditEvent.AuditEventSeverity.INFORMATIONAL
                        : AuditEvent.AuditEventSeverity.WARNING);
        traza.setRecorded(Date.from(acceso.cuando()));

        traza.getOutcome()
                .setCode(new Coding()
                        .setSystem(DESENLACES)
                        .setCode(acceso.desenlace().codigo())
                        .setDisplay(acceso.desenlace().nombre()));
        acceso.desenlace().detalle().ifPresent(motivo -> traza.getOutcome()
                .addDetail(new CodeableConcept().setText(motivo)));

        traza.addAgent(quienLlamo(acceso));

        // ⚠️ `source.observer` es `1..1` en el estándar. Va por identificador y no por referencia: el
        // servidor no se publica a sí mismo como `Device` en su propia proyección, y una referencia a
        // un recurso que no existe rompería la integridad referencial de todo lo que se escriba.
        traza.getSource()
                .getObserver()
                .getIdentifier()
                .setSystem(SISTEMA_SERVIDOR)
                .setValue(observador);
        traza.getSource().addType(codigo(TIPOS_DE_TRAZA, "rest", "RESTful Operation"));

        acceso.paciente().ifPresent(persona -> traza.setPatient(new Reference(persona)));

        for (Acceso.Recurso recurso : acceso.recursos()) {
            traza.addEntity()
                    .setWhat(referenciaA(recurso))
                    .setRole(
                            recurso.esDe("Patient")
                                    ? codigo(PAPEL_DE_OBJETO, "1", "Patient")
                                    : codigo(PAPEL_DE_OBJETO, "4", "Domain Resource"));
        }

        return traza;
    }

    /**
     * La referencia al recurso tocado — literal si existe, lógica si solo se intentó.
     *
     * <p>⚠️ <strong>Esto no es cosmética, y costó un fallo:</strong> HAPI comprueba la integridad
     * referencial al escribir, así que un {@code AuditEvent} con {@code entity.what} apuntando a un
     * recurso que no existe <strong>se rechaza</strong>. El resultado es exactamente el peor posible:
     * la traza del acceso a un id inventado —o a uno recién borrado— es la que más falta hace para
     * investigar, y es justo la única que el servidor se niega a guardar.
     *
     * <p>La salida no es apagar la integridad referencial de todo el almacén, que protege de verdad al
     * resto de recursos. Es escribir lo que de verdad se sabe: lo que el servidor devolvió existe y va
     * como <strong>referencia literal</strong>; lo que solo se pidió puede no existir —y cuando el
     * acceso falla, lo normal es que no exista— y va como <strong>referencia lógica</strong>, con el
     * tipo y el identificador. FHIR admite las dos formas, y la lógica es además la más honesta: dice
     * «se pidió esto» sin afirmar que eso esté ahí.
     *
     * <p>Ninguna de las dos formas construye la referencia copiando la del recurso original: eso
     * arrastraría su {@code display}, que en un {@code Reference} a {@code Patient} es el nombre.
     */
    private static Reference referenciaA(Acceso.Recurso recurso) {
        return recurso.devuelto()
                ? new Reference(recurso.referencia())
                : referenciaLogica(recurso.referencia(), SISTEMA_RECURSO_PEDIDO);
    }

    /**
     * Nombra un recurso <strong>sin afirmar que exista en este servidor</strong>: el tipo va en
     * {@code Reference.type} y el {@code Tipo/id} entero en {@code Reference.identifier}.
     *
     * <p>Se busca con el modificador {@code :identifier} —
     * {@code AuditEvent?agent:identifier=<sistema>|Practitioner/dra-alvarez}—, que es una consulta más
     * larga a cambio de que la traza llegue a escribirse.
     */
    private static Reference referenciaLogica(String tipoBarraId, String sistema) {
        Reference logica = new Reference();
        int barra = tipoBarraId.indexOf('/');
        if (barra > 0) {
            logica.setType(tipoBarraId.substring(0, barra));
        }
        logica.getIdentifier().setSystem(sistema).setValue(tipoBarraId);
        return logica;
    }

    /**
     * Quién pidió el acto — y <strong>nunca por referencia literal</strong>, ni siquiera cuando el
     * testigo trae un {@code fhirUser}.
     *
     * <p>⚠️ <strong>Es la misma trampa de {@code referenciaA}, y volvió a morder por otro camino</strong>
     * (`adr-0030`): {@code agent.who = Reference("Practitioner/dra-alvarez")} hace que HAPI rechace la
     * traza entera si ese facultativo no está en el directorio del laboratorio. Y quien más interesa
     * registrar es exactamente ése: <strong>alguien con un testigo válido que no figura en nuestro
     * directorio</strong>. Con la referencia literal, su acceso es el único que no deja rastro.
     *
     * <p>El fondo del asunto es de autoridad, no de robustez: el {@code fhirUser} lo afirma el
     * proveedor de identidad, no este servidor. Escribirlo como referencia literal diría «este recurso,
     * el mío», que es una afirmación que el laboratorio no está en condiciones de hacer. Como
     * referencia lógica dice lo que de verdad pasó: el testigo se presentó con este nombre.
     *
     * <p>Un cliente de sistema va también por identificador, y por un motivo distinto: en SMART Backend
     * Services no hay nadie detrás de la pantalla, y forzar una referencia a un recurso de persona
     * diría que sí lo había.
     */
    private static AuditEvent.AuditEventAgentComponent quienLlamo(Acceso acceso) {
        AuditEvent.AuditEventAgentComponent agente = new AuditEvent.AuditEventAgentComponent();
        agente.setRequestor(true);

        if (acceso.comoUsuario().isPresent()) {
            agente.setType(codigo(PAPEL_DE_AGENTE, "humanuser", "human user"));
            agente.setWho(referenciaLogica(acceso.comoUsuario().get(), SISTEMA_USUARIO_DEL_TESTIGO));
        } else {
            agente.setType(codigo(PAPEL_DE_AGENTE, "dataprocessor", "data processor"));
            agente.getWho()
                    .getIdentifier()
                    .setSystem(SISTEMA_CLIENTE)
                    .setValue(acceso.quien().orElse(SIN_IDENTIFICAR));
        }

        // ⚠️ R5: `network[x]`. En R4 era un elemento `network` con `address` y `type` dentro, y el
        // código que se copie de allí no compila aquí.
        acceso.desdeDonde().ifPresent(direccion -> agente.setNetwork(new org.hl7.fhir.r5.model.StringType(direccion)));
        return agente;
    }

    private static CodeableConcept codigo(String sistema, String valor, String nombre) {
        return new CodeableConcept()
                .addCoding(new Coding().setSystem(sistema).setCode(valor).setDisplay(nombre));
    }
}
