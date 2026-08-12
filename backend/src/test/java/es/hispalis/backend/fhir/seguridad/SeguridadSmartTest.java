package es.hispalis.backend.fhir.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CapabilityStatement;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Practitioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SMART on FHIR de punta a punta: quién entra, qué puede hacer y —sobre todo— de quién.
 *
 * <p>El test que da sentido a los demás es
 * {@link #un_testigo_del_paciente_a_no_puede_leer_al_paciente_b()}. Ahí se ve la afirmación del
 * proyecto convertida en dos respuestas HTTP distintas: <strong>un scope concedido no garantiza los
 * datos</strong>. El testigo lleva {@code patient/*.rs} —permiso de lectura sobre todos los tipos, y
 * {@link AutorizacionSmart} se lo concede— y aun así el {@code GET} al otro paciente devuelve
 * {@code 403}, porque quien decide de quién son los datos es {@link ConsentimientoDelPaciente}, aquí,
 * en el servidor FHIR. No en el proxy, que no sabe qué es un compartimento, y no en el servidor de
 * identidad, que ya hizo lo suyo al emitir el testigo.
 *
 * <p>La identidad es {@link ServidorDeIdentidadDePruebas}: un JWKS de verdad y testigos firmados de
 * verdad, para que lo que se ejerza sea el camino de producción entero —descubrimiento, firma,
 * emisor, caducidad y audiencia— y no una versión de test de él.
 *
 * <p><strong>Las dos formas de decir que no.</strong> A una lectura directa se le contesta
 * {@code 403}; de una búsqueda se omite el recurso en silencio. La diferencia no es estética:
 * responder «hay tres que no te enseño» ya cuenta algo de quien no lo autorizó, y con unas cuantas
 * búsquedas bien elegidas se reconstruye lo que se quería ocultar. Las dos están probadas.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=true",
            "hispalis.seguridad.audiencias=" + ServidorDeIdentidadDePruebas.AUDIENCIA,
            "hispalis.seguridad.tiempo-de-espera=PT2S"
        })
class SeguridadSmartTest extends TestDeIntegracion {

    private static final ServidorDeIdentidadDePruebas IDENTIDAD = ServidorDeIdentidadDePruebas.elDeSiempre();

    private static final String SYSTEM_NHC = "https://aojeda006.github.io/HispaLIS/sid/nhc";
    private static final String APELLIDO = "Peñarroya Muñoz";

    /**
     * Cada clase de test tiene su propio tramo de NHC: la base de datos es una sola para toda la
     * ejecución, y dos clases que arranquen en el mismo número se pisan con un {@code 409} que parece
     * un fallo del test que llegó segundo. El 35 estaba libre.
     */
    private static final AtomicInteger SIGUIENTE_NHC = new AtomicInteger(35_000_000);

    /** Lo que lleva un profesional en su testigo: leer todo y dar de alta lo que el alta necesita. */
    private static final String FACULTATIVA = "Practitioner/dra-alvarez";

    private static final String SCOPES_DEL_FACULTATIVO =
            "openid fhirUser launch user/*.rs user/Patient.c user/Practitioner.c user/ServiceRequest.c";

    private static String pacienteA;
    private static String pacienteB;

    private final FhirContext contexto = FhirContext.forR5();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void apuntarAlServidorDeIdentidad(DynamicPropertyRegistry registro) {
        registro.add("hispalis.seguridad.emisor", IDENTIDAD::emisor);
    }

    // ---------------------------------------------------------------- quién entra

    @Test
    void sin_testigo_la_api_contesta_401_con_operationoutcome() {
        ResponseEntity<String> respuesta =
                rest.exchange("/fhir/Patient", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(respuesta.getHeaders().getFirst("WWW-Authenticate"))
                .as("sin esta cabecera el cliente no sabe distinguir «no me he identificado» de «no me quieres»")
                .startsWith("Bearer");
        assertThat(operationOutcomeDe(respuesta).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.LOGIN);
    }

    @Test
    void un_testigo_caducado_no_vale() {
        ResponseEntity<String> respuesta = leer("/fhir/Patient", IDENTIDAD.testigoCaducado("dra.alvarez", "user/*.rs"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * La comprobación que más se olvida y la que más cara sale: un testigo <strong>legítimo</strong>,
     * bien firmado por el mismo emisor y sin caducar, pero emitido para otro servidor de recursos.
     * Sin validar {@code aud}, cualquier aplicación con acceso a otro servidor del mismo <em>realm</em>
     * entraría aquí con el testigo que ya tiene.
     */
    @Test
    void un_testigo_emitido_para_otro_servidor_de_recursos_no_vale_aqui() {
        ResponseEntity<String> respuesta =
                leer("/fhir/Patient", IDENTIDAD.testigoParaOtroServidor("dra.alvarez", "user/*.rs"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------- lo que es público

    @Test
    void el_descubrimiento_smart_es_publico_y_declara_solo_lo_que_se_cumple() throws Exception {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/.well-known/smart-configuration", String.class);

        assertThat(respuesta.getStatusCode())
                .as("una aplicación lee esto antes de tener testigo: pedirle uno sería pedir que adivine")
                .isEqualTo(HttpStatus.OK);

        JsonNode documento = json.readTree(respuesta.getBody());
        assertThat(documento.get("issuer").asText()).isEqualTo(IDENTIDAD.emisor());
        assertThat(documento.get("authorization_endpoint").asText()).contains("/protocol/openid-connect/auth");
        assertThat(documento.get("token_endpoint").asText()).contains("/protocol/openid-connect/token");
        assertThat(documento.get("jwks_uri").asText()).isEqualTo(IDENTIDAD.emisor() + "/certs");

        assertThat(textos(documento, "code_challenge_methods_supported"))
                .as("la norma dice que un servidor NO DEBE soportar `plain`, y el realm lo anuncia igual")
                .containsExactly("S256");
        assertThat(textos(documento, "capabilities"))
                .contains("launch-ehr", "client-public", "client-confidential-asymmetric", "permission-v2")
                .doesNotContain("permission-offline", "client-confidential-symmetric");
    }

    @Test
    void el_capabilitystatement_es_publico_y_dice_donde_se_autoriza() {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/metadata", String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        CapabilityStatement declaracion =
                contexto.newJsonParser().parseResource(CapabilityStatement.class, respuesta.getBody());

        var seguridad = declaracion.getRestFirstRep().getSecurity();
        assertThat(seguridad.getServiceFirstRep().getCodingFirstRep().getCode())
                .as("un cliente FHIR genérico, que no sabe de SMART, se entera aquí de que hace falta OAuth2")
                .isEqualTo("SMART-on-FHIR");

        var uris = seguridad.getExtensionByUrl("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
        assertThat(uris).isNotNull();
        assertThat(uris.getExtensionByUrl("authorize").getValue().primitiveValue())
                .isEqualTo(IDENTIDAD.emisor() + "/protocol/openid-connect/auth");
        assertThat(uris.getExtensionByUrl("token").getValue().primitiveValue())
                .isEqualTo(IDENTIDAD.emisor() + "/protocol/openid-connect/token");
    }

    // ---------------------------------------------------------------- qué se puede hacer

    @Test
    void un_testigo_de_solo_lectura_no_puede_escribir() {
        String soloLectura = IDENTIDAD.testigo("dra.alvarez", "user/*.rs", null, "Practitioner/dra-alvarez");

        ResponseEntity<String> respuesta = crear(pacienteDePrueba(nuevoNhc()), soloLectura);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(operationOutcomeDe(respuesta).getIssueFirstRep().getDiagnostics())
                .containsIgnoringCase("scope");
    }

    /**
     * Un sufijo desordenado no se aproxima: no concede nada. Es la regla de {@link AmbitoSmart} vista
     * desde el cable — {@code .sr} pedía leer y buscar, y si se «corrigiera» a {@code .rs} el servidor
     * estaría decidiendo por el cliente qué quiso pedir.
     */
    @Test
    void un_scope_desordenado_no_concede_nada_en_el_cable() {
        String raro = IDENTIDAD.testigo("dra.alvarez", "user/Patient.sr", null, "Practitioner/dra-alvarez");

        assertThat(leer("/fhir/Patient", raro).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- de quién son los datos

    /**
     * El test del consentimiento cruzado. Mismo testigo, mismos <em>scopes</em>, dos respuestas.
     *
     * <p>{@code patient/*.rs} concede leer <em>todos los tipos</em>; lo que no concede —ni puede— es
     * <em>de quién</em>. Eso lo dice el contexto de lanzamiento, que viaja firmado dentro del testigo,
     * y lo aplica el servidor FHIR recurso a recurso antes de que ninguno salga por el cable.
     */
    @Test
    void un_testigo_del_paciente_a_no_puede_leer_al_paciente_b() {
        darDeAltaLosDosPacientes();
        String testigoDeA = IDENTIDAD.testigo("paciente.demo", "patient/*.rs", pacienteA, "Patient/" + pacienteA);

        assertThat(leer("/fhir/Patient/" + pacienteA, testigoDeA).getStatusCode())
                .as("el paciente A sí puede leerse a sí mismo: el scope y el contexto coinciden")
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> cruzada = leer("/fhir/Patient/" + pacienteB, testigoDeA);

        assertThat(cruzada.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        OperationOutcome resultado = operationOutcomeDe(cruzada);
        assertThat(resultado.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(resultado.getIssueFirstRep().getDiagnostics()).containsIgnoringCase("no pertenece al paciente");
        assertThat(cruzada.getBody())
                .as("ni un dato del paciente B puede aparecer en la negativa")
                .doesNotContain(APELLIDO);
    }

    /**
     * En una búsqueda no se contesta que no: se omite. Decir «hay tres que no te enseño» ya es contar
     * algo de quien no lo autorizó.
     */
    @Test
    void una_busqueda_omite_en_silencio_lo_que_no_es_del_paciente_en_contexto() {
        darDeAltaLosDosPacientes();
        String testigoDeA = IDENTIDAD.testigo("paciente.demo", "patient/*.rs", pacienteA, "Patient/" + pacienteA);

        ResponseEntity<String> respuesta = leer("/fhir/Patient?family=" + APELLIDO.replace(' ', '+'), testigoDeA);

        assertThat(respuesta.getStatusCode())
                .as("una búsqueda filtrada es una búsqueda correcta, no un error")
                .isEqualTo(HttpStatus.OK);
        Bundle resultado = contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
        assertThat(idsDe(resultado)).contains(pacienteA).doesNotContain(pacienteB);
    }

    /**
     * Un testigo con ámbito {@code patient/} y sin paciente en el contexto está mal emitido. Lo que
     * corresponde es no dejarle ver nada: la lectura amable —«sin restricción, luego todo»— convierte
     * un error de configuración del servidor de identidad en una fuga de datos.
     */
    @Test
    void un_testigo_de_paciente_sin_contexto_de_lanzamiento_no_ve_nada() {
        darDeAltaLosDosPacientes();
        String malEmitido = IDENTIDAD.testigo("paciente.demo", "patient/*.rs", null, null);

        assertThat(leer("/fhir/Patient/" + pacienteA, malEmitido).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * El consentimiento del paciente solo aplica a los testigos de paciente. Un cliente de sistema
     * —el motor de integración— no se lanza sobre nadie y tiene que poder leer a cualquiera; lo que
     * lo limita son sus <em>scopes</em>, no un contexto que no existe.
     */
    @Test
    void un_testigo_de_sistema_no_esta_atado_a_ningun_paciente() {
        darDeAltaLosDosPacientes();
        String delMotor = IDENTIDAD.testigo("hispalis-motor", "system/Patient.crus", null, null);

        assertThat(leer("/fhir/Patient/" + pacienteA, delMotor).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(leer("/fhir/Patient/" + pacienteB, delMotor).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Y sus scopes sí lo limitan: {@code system/Patient.crus} no incluye borrar. */
    @Test
    void un_testigo_de_sistema_sin_permiso_de_borrado_no_borra() {
        darDeAltaLosDosPacientes();
        String delMotor = IDENTIDAD.testigo("hispalis-motor", "system/Patient.crus", null, null);

        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/Patient/" + pacienteB, HttpMethod.DELETE, new HttpEntity<>(conTestigo(delMotor)), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Un suscriptor que puede leer su {@code Subscription} puede preguntar por ella.
     *
     * <p>{@code $status} y {@code $events} son lecturas: en qué estado quedó la suscripción, por qué
     * falló la última entrega y qué números de evento se perdió mientras estuvo caído. Sin regla
     * propia, un cliente con {@code system/Subscription.crs} <strong>creaba la suscripción y se
     * llevaba un {@code 403} al preguntar por ella</strong> — y es justo el que más lo necesita.
     *
     * <p>Se prueba sobre una suscripción que no existe, y a propósito: lo que se comprueba es la
     * <strong>autorización</strong>, que corre antes que el proveedor. Sin permiso, {@code 403};
     * con permiso, la operación se ejecuta y contesta lo que le corresponde — {@code 404}, porque no
     * hay tal suscripción. Montar una de verdad probaría lo mismo y además el tópico, la entrega y
     * el receptor, que ya tienen sus tests.
     */
    @Test
    void el_estado_de_una_suscripcion_se_pregunta_con_el_permiso_de_leerla() {
        String sinSuscripciones = IDENTIDAD.testigo("hispalis-motor", "system/Patient.crus", null, null);
        String delSuscriptor = IDENTIDAD.testigo("his-suscriptor", "system/Subscription.crs", null, null);

        assertThat(leer("/fhir/Subscription/no-existe/$status", sinSuscripciones)
                        .getStatusCode())
                .as("sin permiso sobre Subscription, ni el estado")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(leer("/fhir/Subscription/no-existe/$status", delSuscriptor).getStatusCode())
                .as("con permiso, la autorización deja pasar y contesta el proveedor: no hay tal suscripción")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(leer("/fhir/Subscription/no-existe/$events", delSuscriptor).getStatusCode())
                .as("y lo mismo con `$events`, que es la otra mitad: qué me he perdido")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- utilidades

    /**
     * Los dos pacientes del test, dados de alta una sola vez con un testigo de facultativo.
     *
     * <p>Se crean por la API y no por SQL a propósito: si el alta se hiciera por detrás, el test no
     * probaría que un profesional autenticado puede escribir, que es la otra mitad de lo que hace
     * falta para que la web funcione.
     */
    private void darDeAltaLosDosPacientes() {
        if (pacienteA != null) {
            return;
        }
        String delFacultativo =
                IDENTIDAD.testigo("dra.alvarez", SCOPES_DEL_FACULTATIVO, null, "Practitioner/dra-alvarez");
        pacienteA = idDe(crear(pacienteDePrueba(nuevoNhc()), delFacultativo));
        pacienteB = idDe(crear(pacienteDePrueba(nuevoNhc()), delFacultativo));
    }

    /**
     * Validar un resultado exige un <em>scope</em>, y el que hace falta no es solo el evidente.
     *
     * <p>{@code $validar} firma un {@code Observation} <strong>y escribe un {@code Provenance}</strong>
     * en la misma transacción. Con la seguridad encendida eso lo comprueba el interceptor de
     * autorización en {@code STORAGE_PRESTORAGE_RESOURCE_CREATED}, así que un testigo con permiso
     * sobre {@code Observation} y sin permiso sobre {@code Provenance} se lleva un {@code 403} —
     * pese a que la operación estaba autorizada— y el mensaje no dice qué recurso lo provocó.
     *
     * <p>Se descubrió recorriendo el circuito v2 contra el `compose` con seguridad. No lo veía
     * ningún test porque {@code TestDeIntegracion} apaga la seguridad, y ningún cliente lo veía
     * porque **ninguno llama todavía a {@code $validar}**: la web no tiene pantalla de validación.
     * La operación llevaba desde el ítem 18 sin poder ejecutarse con la seguridad puesta.
     */
    @Test
    void validar_un_resultado_exige_el_scope_y_escribe_tambien_la_procedencia() {
        String delMotor = IDENTIDAD.testigo(
                "hispalis-motor",
                "system/Patient.crus system/ServiceRequest.cs system/Specimen.cs system/Observation.crs",
                null,
                null);
        CircuitoDePrueba circuito = new CircuitoDePrueba(rest, contexto, delMotor);

        // Quien pide y quien firma tiene que existir, y darlo de alta con un id elegido es un `PUT`:
        // de ahí `user/Practitioner.u`. Es el mismo camino que `infra/fhir/sembrar-facultativos.sh`,
        // y la misma razón por la que el `OML^O21` no entraba contra la pila con seguridad.
        sembrarALaFacultativa();

        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, FACULTATIVA));
        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, FACULTATIVA));

        // Sin `user/Observation.u` no se valida, que es lo correcto.
        assertThat(validar(resultado, IDENTIDAD.testigo("dra.alvarez", SCOPES_DEL_FACULTATIVO, null, FACULTATIVA))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Y con él sí, aunque la operación escriba por debajo un recurso de otro tipo.
        ResponseEntity<String> firmado = validar(
                resultado,
                IDENTIDAD.testigo("dra.alvarez", SCOPES_DEL_FACULTATIVO + " user/Observation.u", null, FACULTATIVA));

        assertThat(firmado.getStatusCode())
                .as("con permiso de actualizar Observation, `$validar` tiene que pasar: %s", firmado.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(firmado.getBody()).contains("\"status\":\"final\"");

        // Y la concesión no abre ninguna puerta: con el MISMO testigo, escribir una procedencia a
        // mano sigue estando prohibido. Si esto dejara de ser cierto, un cliente podría certificar
        // una validación que no ha ocurrido.
        HttpHeaders aMano = conTestigo(
                IDENTIDAD.testigo("dra.alvarez", SCOPES_DEL_FACULTATIVO + " user/Observation.u", null, FACULTATIVA));
        aMano.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<String> inventada = rest.exchange(
                "/fhir/Provenance",
                HttpMethod.POST,
                new HttpEntity<>(
                        """
                        {"resourceType":"Provenance","target":[{"reference":"%s"}],                        "recorded":"2026-08-09T10:00:00+02:00",                        "agent":[{"who":{"reference":"%s"}}]}"""
                                .formatted(resultado, FACULTATIVA),
                        aMano),
                String.class);

        assertThat(inventada.getStatusCode())
                .as("una procedencia escrita por el cliente certificaría algo que aquí no ha pasado")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** Da de alta a `dra-alvarez` con un `PUT` de id elegido, que es lo que exige `.u`. */
    private void sembrarALaFacultativa() {
        Practitioner quien = new Practitioner();
        quien.setId("dra-alvarez");
        quien.addName(new HumanName().setFamily("Álvarez Peña").addGiven("Marta"));

        HttpHeaders cabeceras = conTestigo(
                IDENTIDAD.testigo("dra.alvarez", SCOPES_DEL_FACULTATIVO + " user/Practitioner.u", null, FACULTATIVA));
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<String> alta = rest.exchange(
                "/fhir/" + FACULTATIVA,
                HttpMethod.PUT,
                new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(quien), cabeceras),
                String.class);

        assertThat(alta.getStatusCode())
                .as("sin `user/Practitioner.u` el directorio no se puede sembrar: %s", alta.getBody())
                .isIn(HttpStatus.OK, HttpStatus.CREATED);
    }

    private ResponseEntity<String> validar(String resultado, String testigo) {
        HttpHeaders cabeceras = conTestigo(testigo);
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo =
                """
                {"resourceType":"Parameters","parameter":[                {"name":"facultativo","valueReference":{"reference":"%s"}}]}"""
                        .formatted(FACULTATIVA);

        return rest.exchange(
                "/fhir/" + resultado + "/$validar", HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private ResponseEntity<String> crear(Patient paciente, String testigo) {
        HttpHeaders cabeceras = conTestigo(testigo);
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(paciente);

        return rest.exchange("/fhir/Patient", HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private ResponseEntity<String> leer(String ruta, String testigo) {
        return rest.exchange(ruta, HttpMethod.GET, new HttpEntity<>(conTestigo(testigo)), String.class);
    }

    private static HttpHeaders conTestigo(String testigo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(testigo);
        return cabeceras;
    }

    private static String idDe(ResponseEntity<String> alta) {
        assertThat(alta.getStatusCode())
                .as("el alta de apoyo del test tiene que funcionar")
                .isEqualTo(HttpStatus.CREATED);
        String location = alta.getHeaders().getFirst(HttpHeaders.LOCATION);
        String sinHistorial = location.substring(0, location.indexOf("/_history"));
        return sinHistorial.substring(sinHistorial.lastIndexOf('/') + 1);
    }

    private static List<String> idsDe(Bundle resultado) {
        return resultado.getEntry().stream()
                .map(entrada -> entrada.getResource().getIdElement().getIdPart())
                .toList();
    }

    private OperationOutcome operationOutcomeDe(ResponseEntity<String> respuesta) {
        return contexto.newJsonParser().parseResource(OperationOutcome.class, respuesta.getBody());
    }

    private static List<String> textos(JsonNode documento, String campo) {
        return java.util.stream.StreamSupport.stream(documento.get(campo).spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static String nuevoNhc() {
        return String.valueOf(SIGUIENTE_NHC.incrementAndGet());
    }

    private static Patient pacienteDePrueba(String nhc) {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SYSTEM_NHC).setValue(nhc);
        paciente.addName(new HumanName().setFamily(APELLIDO).addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        paciente.setBirthDateElement(new org.hl7.fhir.r5.model.DateType("1981-03-14"));
        paciente.setActive(true);
        return paciente;
    }
}
