package es.hispalis.backend.fhir.resultado;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Specimen;
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
 * Cuándo se midió y quién lo hizo: los dos {@code Must Support} que el laboratorio perdía.
 *
 * <p>Hasta ahora el recurso podía traer {@code effective[x]} y {@code performer} y la proyección los
 * tiraba por el camino. No daba error —el validador solo avisaba— y el resultado se publicaba con
 * una cifra correcta y sin fecha ni autor: no se sabía si era de esta mañana o del mes pasado, ni a
 * quién reclamar.
 *
 * <p>{@code Must Support} <strong>no significa obligatorio</strong>: el perfil los declara {@code
 * 0..1} y {@code 0..*}, así que un resultado sin ellos se acepta igual. Lo que significa es que si
 * llegan, el servidor los guarda y los devuelve. Eso es lo que se prueba aquí, junto con lo que
 * <strong>no</strong> se hace: inventarlos cuando no vienen.
 */
class MedicionDelResultadoTest extends TestDeIntegracion {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(70_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void el_resultado_conserva_cuando_se_midio_y_quien_lo_hizo() {
        String paciente = crear(pacienteDePrueba());
        String muestra = crear(muestraDePrueba(paciente));
        String laboratorio = crear(laboratorioDePrueba());
        Instant medido = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        Observation enviado = resultadoDePrueba(paciente, muestra);
        enviado.setEffective(new DateTimeType(Date.from(medido)));
        enviado.addPerformer(new Reference(laboratorio));

        Observation publicado = leer(crear(enviado));

        assertThat(publicado.getEffectiveDateTimeType().getValue().toInstant()).isEqualTo(medido);
        assertThat(publicado.getPerformerFirstRep().getReference()).isEqualTo(laboratorio);
    }

    @Test
    void la_medicion_llega_hasta_el_dominio_y_no_se_queda_en_la_proyeccion() {
        String paciente = crear(pacienteDePrueba());
        String muestra = crear(muestraDePrueba(paciente));
        String laboratorio = crear(laboratorioDePrueba());
        Instant medido = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        Observation enviado = resultadoDePrueba(paciente, muestra);
        enviado.setEffective(new DateTimeType(Date.from(medido)));
        enviado.addPerformer(new Reference(laboratorio));
        String referencia = crear(enviado);

        // Si esto falla, la fecha se publicó pero el núcleo no la tiene: la próxima consulta que
        // pregunte al dominio —un rango de referencia por edad, una búsqueda por fecha— decidirá
        // sin ella.
        String autor = jdbc.queryForObject(
                "SELECT realizado_por FROM dominio.resultado WHERE id = ?",
                String.class,
                java.util.UUID.fromString(referencia.substring("Observation/".length())));
        assertThat(autor).isEqualTo(laboratorio);
    }

    @Test
    void un_resultado_sin_fecha_de_medicion_se_acepta_y_no_se_la_inventa() {
        String paciente = crear(pacienteDePrueba());
        String muestra = crear(muestraDePrueba(paciente));

        Observation publicado = leer(crear(resultadoDePrueba(paciente, muestra)));

        // El perfil los declara opcionales, así que rechazarlo sería que el servidor contradijera a
        // su propia guía. Y rellenar la fecha con la hora de registro sería peor que dejarla
        // vacía: pondría un resultado de ayer entre los de hoy con toda la apariencia de ser bueno.
        assertThat(publicado.hasEffective()).isFalse();
        assertThat(publicado.hasPerformer()).isFalse();
    }

    @Test
    void una_fecha_de_medicion_en_el_futuro_se_rechaza() {
        String paciente = crear(pacienteDePrueba());
        String muestra = crear(muestraDePrueba(paciente));

        Observation enviado = resultadoDePrueba(paciente, muestra);
        enviado.setEffective(new DateTimeType(Date.from(Instant.now().plus(2, ChronoUnit.DAYS))));

        ResponseEntity<String> respuesta = enviar(enviado);

        // Un analizador con el reloj mal puesto no produce un error visible: produce resultados que
        // se colocan al principio de la historia del paciente y se leen como los más recientes.
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(respuesta.getBody()).contains("futuro");
    }

    private String crear(IBaseResource recurso) {
        ResponseEntity<String> respuesta = enviar(recurso);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo preparar el escenario: %s", respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String location = respuesta.getHeaders().getFirst(HttpHeaders.LOCATION);
        return location.substring(0, location.indexOf("/_history"))
                .substring(location.indexOf("/fhir/") + "/fhir/".length());
    }

    private ResponseEntity<String> enviar(IBaseResource recurso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(recurso);

        return rest.exchange(
                "/fhir/" + recurso.fhirType(), HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private Observation leer(String referencia) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + referencia, String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Observation.class, respuesta.getBody());
    }

    private static Patient pacienteDePrueba() {
        Patient paciente = new Patient();
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.NHC)
                .setValue(String.valueOf(SIGUIENTE.incrementAndGet()));
        paciente.addName(new HumanName().setFamily("Muñoz Peña").addGiven("Álvaro"));
        paciente.setGender(Enumerations.AdministrativeGender.MALE);
        return paciente;
    }

    private static Specimen muestraDePrueba(String paciente) {
        Specimen muestra = new Specimen();
        muestra.getAccessionIdentifier().setValue("A" + SIGUIENTE.incrementAndGet());
        muestra.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        muestra.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(SANGRE_VENOSA)));
        muestra.setSubject(new Reference(paciente));
        return muestra;
    }

    private static Organization laboratorioDePrueba() {
        Organization laboratorio = new Organization();
        laboratorio
                .addIdentifier()
                .setSystem("https://aojeda006.github.io/HispaLIS/sid/nica")
                .setValue("NICA" + SIGUIENTE.incrementAndGet());
        laboratorio.setName("Laboratorio HispaLIS");
        return laboratorio;
    }

    private static Observation resultadoDePrueba(String paciente, String muestra) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.FINAL);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("GLU")));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(muestra));
        resultado.setValue(
                new Quantity().setValue(92).setUnit("mg/dL").setSystem(UCUM).setCode("mg/dL"));
        return resultado;
    }
}
