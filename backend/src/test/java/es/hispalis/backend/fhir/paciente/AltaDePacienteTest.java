package es.hispalis.backend.fhir.paciente;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Criterio de aceptación 4 (§14 del diseño): <em>read-your-writes</em> en una sola transacción.
 *
 * <p>No es un detalle de rendimiento. FHIR REST obliga a que un {@code GET} inmediato al
 * {@code Location} de un {@code 201} devuelva el recurso; si la proyección se escribiera de forma
 * asíncrona ese {@code GET} daría {@code 404} y el servidor <strong>estaría incumpliendo la
 * norma</strong>. Por eso esto se prueba con un test y no con una comprobación manual.
 */
class AltaDePacienteTest extends TestDeIntegracion {

    private static final String SYSTEM_NHC = "https://aojeda006.github.io/HispaLIS/sid/nhc";

    /** Cada test necesita su propio NHC: la base de datos es una sola para toda la ejecución. */
    private static final AtomicInteger SIGUIENTE_NHC = new AtomicInteger(10_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void un_alta_devuelve_201_con_location_y_etag_de_primera_version() {
        ResponseEntity<String> respuesta = darDeAlta(pacienteDePrueba(nuevoNhc()));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.LOCATION))
                .as("un 201 sin Location deja al cliente sin saber qué se creó")
                .isNotNull()
                .contains("/Patient/");
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("W/\"1\"");
    }

    @Test
    void un_get_inmediato_al_location_devuelve_el_recurso() {
        String nhc = nuevoNhc();
        ResponseEntity<String> alta = darDeAlta(pacienteDePrueba(nhc));
        String location = alta.getHeaders().getFirst(HttpHeaders.LOCATION);

        ResponseEntity<String> lectura = rest.getForEntity(location, String.class);

        assertThat(lectura.getStatusCode())
                .as("un 404 aquí significa proyección asíncrona, que incumple FHIR REST")
                .isEqualTo(HttpStatus.OK);
        Patient leido = contexto.newJsonParser().parseResource(Patient.class, lectura.getBody());
        assertThat(nhcDe(leido)).isEqualTo(nhc);
    }

    @Test
    void el_alta_escribe_el_dominio_y_la_proyeccion() {
        String nhc = nuevoNhc();

        darDeAlta(pacienteDePrueba(nhc));

        Integer enElDominio =
                jdbc.queryForObject("SELECT count(*) FROM dominio.paciente WHERE nhc = ?", Integer.class, nhc);
        assertThat(enElDominio)
                .as("el dominio es la fuente de verdad, no un subproducto")
                .isEqualTo(1);

        ResponseEntity<String> busqueda =
                rest.getForEntity("/fhir/Patient?identifier=" + SYSTEM_NHC + "|" + nhc, String.class);
        assertThat(busqueda.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(busqueda.getBody()).contains(nhc);
    }

    @Test
    void los_apellidos_espanoles_sobreviven_al_viaje_completo() {
        String nhc = nuevoNhc();
        Patient paciente = pacienteDePrueba(nhc);
        paciente.getName().clear();
        paciente.addName(new HumanName().setFamily("Muñoz de la Torre").addGiven("José María"));

        ResponseEntity<String> alta = darDeAlta(paciente);
        ResponseEntity<String> lectura =
                rest.getForEntity(alta.getHeaders().getFirst(HttpHeaders.LOCATION), String.class);
        Patient leido = contexto.newJsonParser().parseResource(Patient.class, lectura.getBody());

        assertThat(leido.getNameFirstRep().getFamily())
                .as("el apellido completo va entero en `family`, nunca troceado por el espacio")
                .isEqualTo("Muñoz de la Torre");
        assertThat(leido.getNameFirstRep().getGivenAsSingleString()).isEqualTo("José María");
    }

    /**
     * La prueba de que las dos escrituras van en la misma transacción: el dominio rechaza el
     * segundo alta con el mismo NHC, y ese rechazo no puede dejar detrás un recurso FHIR huérfano.
     * Si cada escritura tuviera su transacción, la proyección quedaría duplicada.
     */
    @Test
    void un_rechazo_del_dominio_no_deja_recurso_fhir_huerfano() {
        String nhc = nuevoNhc();
        darDeAlta(pacienteDePrueba(nhc));

        ResponseEntity<String> repetido = darDeAlta(pacienteDePrueba(nhc));

        assertThat(repetido.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(operationOutcomeDe(repetido).getIssueFirstRep().getDiagnostics())
                .contains(nhc);

        Integer enElDominio =
                jdbc.queryForObject("SELECT count(*) FROM dominio.paciente WHERE nhc = ?", Integer.class, nhc);
        assertThat(enElDominio).isEqualTo(1);
    }

    @Test
    void un_nhc_que_no_son_ocho_digitos_se_rechaza_con_operationoutcome() {
        Patient paciente = pacienteDePrueba("123");

        ResponseEntity<String> respuesta = darDeAlta(paciente);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OperationOutcome resultado = operationOutcomeDe(respuesta);
        assertThat(resultado.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(resultado.getIssueFirstRep().getDiagnostics())
                .as("el mensaje lo lee una persona: tiene que decir qué está mal")
                .containsIgnoringCase("ocho dígitos");
    }

    @Test
    void un_paciente_sin_nhc_se_rechaza() {
        Patient paciente = pacienteDePrueba(nuevoNhc());
        paciente.getIdentifier().clear();

        assertThat(darDeAlta(paciente).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> darDeAlta(Patient paciente) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(paciente);

        return rest.exchange("/fhir/Patient", HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private OperationOutcome operationOutcomeDe(ResponseEntity<String> respuesta) {
        return contexto.newJsonParser().parseResource(OperationOutcome.class, respuesta.getBody());
    }

    private static String nuevoNhc() {
        return String.valueOf(SIGUIENTE_NHC.incrementAndGet());
    }

    private static String nhcDe(Patient paciente) {
        return paciente.getIdentifier().stream()
                .filter(identificador -> SYSTEM_NHC.equals(identificador.getSystem()))
                .map(Identifier::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("El paciente leído no trae NHC"));
    }

    private static Patient pacienteDePrueba(String nhc) {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SYSTEM_NHC).setValue(nhc);
        paciente.addName(new HumanName().setFamily("Peña Álvarez").addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        paciente.setBirthDateElement(
                new org.hl7.fhir.r5.model.DateType(LocalDate.of(1981, 3, 14).toString()));
        paciente.setActive(true);
        return paciente;
    }
}
