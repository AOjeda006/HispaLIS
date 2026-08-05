package es.hispalis.backend.fhir.paciente;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
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
 * Criterio de aceptación 7 (§14 del diseño): concurrencia optimista.
 *
 * <p>Dos administrativos abren la ficha del mismo paciente, los dos corrigen algo y los dos guardan.
 * Sin control de versión, el segundo pisa al primero <strong>sin que nadie se entere</strong>: la
 * corrección del primero desaparece y el sistema no da ningún error. Con {@code If-Match}, el
 * segundo recibe un {@code 412} y puede releer y decidir.
 *
 * <p>Se prueba además que el {@code PUT} pasa por el núcleo. Si fuese el {@code update} heredado de
 * HAPI, escribiría la proyección y dejaría el dominio atrás: dos mitades diciendo cosas distintas y
 * nada avisando.
 */
class ConcurrenciaOptimistaTest extends TestDeIntegracion {

    private static final AtomicInteger SIGUIENTE_NHC = new AtomicInteger(40_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void con_la_version_vigente_actualiza_y_sube_el_versionid() {
        String nhc = nuevoNhc();
        String referencia = crear(nhc);

        ResponseEntity<String> respuesta = actualizar(referencia, corregido(nhc, "Muñoz de la Torre"), "W/\"1\"");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("W/\"2\"");
    }

    @Test
    void con_una_version_obsoleta_devuelve_412() {
        String nhc = nuevoNhc();
        String referencia = crear(nhc);
        actualizar(referencia, corregido(nhc, "Primera corrección"), "W/\"1\"");

        // El segundo administrativo sigue creyendo que va por la versión 1.
        ResponseEntity<String> tarde = actualizar(referencia, corregido(nhc, "Segunda corrección"), "W/\"1\"");

        assertThat(tarde.getStatusCode())
                .as("sin este 412, la corrección del primero desaparecería sin dejar rastro")
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);

        // Y lo que quedó guardado es lo del primero, no lo del segundo.
        Patient vigente = leer(referencia);
        assertThat(vigente.getNameFirstRep().getFamily()).isEqualTo("Primera corrección");
    }

    @Test
    void la_actualizacion_pasa_por_el_dominio_y_no_solo_por_la_proyeccion() {
        String nhc = nuevoNhc();
        String referencia = crear(nhc);

        actualizar(referencia, corregido(nhc, "Álvarez Peña"), "W/\"1\"");

        String enElDominio =
                jdbc.queryForObject("SELECT apellidos FROM dominio.paciente WHERE nhc = ?", String.class, nhc);
        assertThat(enElDominio)
                .as("si esto falla, el PUT escribió la proyección y dejó el dominio atrás")
                .isEqualTo("Álvarez Peña");
    }

    @Test
    void el_nhc_de_un_paciente_no_se_puede_cambiar() {
        String nhc = nuevoNhc();
        String referencia = crear(nhc);

        // El NHC es lo que une al paciente con las muestras que ya circulan y los resultados ya
        // emitidos. Cambiarlo rompería esa cadena en silencio.
        ResponseEntity<String> intento = actualizar(referencia, corregido(nuevoNhc(), "Peña Álvarez"), "W/\"1\"");

        assertThat(intento.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(intento.getBody()).contains(nhc);
    }

    private String crear(String nhc) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(corregido(nhc, "Peña Álvarez"));

        ResponseEntity<String> alta =
                rest.exchange("/fhir/Patient", HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
        assertThat(alta.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String location = alta.getHeaders().getFirst(HttpHeaders.LOCATION);
        return location.substring(0, location.indexOf("/_history"))
                .substring(location.indexOf("/fhir/") + "/fhir/".length());
    }

    private ResponseEntity<String> actualizar(String referencia, Patient paciente, String versionEsperada) {
        // FHIR exige que el cuerpo de un PUT lleve el mismo id que la URL, y HAPI lo hace cumplir:
        // sin esto responde 400. Es lo que evita actualizar un recurso creyendo que es otro.
        paciente.setId(referencia);

        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        cabeceras.setIfMatch(versionEsperada);
        String cuerpo = contexto.newJsonParser().encodeResourceToString(paciente);

        return rest.exchange("/fhir/" + referencia, HttpMethod.PUT, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private Patient leer(String referencia) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + referencia, String.class);
        return contexto.newJsonParser().parseResource(Patient.class, respuesta.getBody());
    }

    private static String nuevoNhc() {
        return String.valueOf(SIGUIENTE_NHC.incrementAndGet());
    }

    private static Patient corregido(String nhc, String apellidos) {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc);
        paciente.addName(new HumanName().setFamily(apellidos).addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        return paciente;
    }
}
