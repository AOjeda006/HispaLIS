package es.hispalis.backend.fhir.paciente;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Bundle;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Buscar un paciente por su número de historia <strong>sin escribirlo en la URL</strong>.
 *
 * <p>El invariante del proyecto es tajante: nunca datos identificativos del paciente en URLs, logs ni
 * trazas. Y un {@code GET /fhir/Patient?identifier=…|00000042} los escribe en los cuatro sitios a la
 * vez —la barra del navegador, su historial, el log de accesos del proxy y la traza del servidor—,
 * donde sobreviven a cualquier borrado posterior del dato.
 *
 * <p>FHIR previó justo esto: {@code POST [base]/[tipo]/_search} lleva los mismos criterios en el
 * cuerpo, como formulario. Este test comprueba que <strong>el servidor lo admite</strong>, porque la
 * web del profesional busca así y no de la otra manera; y comprueba de paso que el enlace de
 * paginación que devuelve <strong>tampoco</strong> lleva el identificador, que sería colarlo por la
 * puerta de atrás.
 */
class BusquedaSinPhiEnLaUrlTest extends TestDeIntegracion {

    /**
     * Cada clase de test arranca su contador en una decena de millón distinta y no pueden chocar: la
     * base de datos es la misma para todas, y aquí se comprueba que un número <em>no</em> exista.
     */
    private static final AtomicInteger SIGUIENTE = new AtomicInteger(80_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void el_paciente_se_encuentra_por_su_nhc_llevandolo_en_el_cuerpo() {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());
        crear(pacienteConNhc(nhc));

        Bundle encontrados = buscarPorNhc(nhc);

        assertThat(encontrados.getEntry()).hasSize(1);
        Patient paciente = (Patient) encontrados.getEntryFirstRep().getResource();
        assertThat(paciente.getIdentifierFirstRep().getValue()).isEqualTo(nhc);
        assertThat(paciente.getNameFirstRep().getFamily())
                .as("los apellidos viajan enteros: partirlos convierte a un paciente en otro")
                .isEqualTo("Muñoz de la Torre Álvarez");
    }

    @Test
    void un_nhc_que_no_existe_devuelve_un_bundle_vacio_y_no_un_error() {
        // Que no haya nadie con ese número es una respuesta legítima —el paciente aún no está dado
        // de alta—, no un fallo. Un 404 aquí obligaría al cliente a tratar lo normal como excepción.
        Bundle encontrados = buscarPorNhc(String.valueOf(SIGUIENTE.incrementAndGet()));

        assertThat(encontrados.getTotal()).isZero();
        assertThat(encontrados.getEntry()).isEmpty();
    }

    @Test
    void el_enlace_de_paginacion_no_devuelve_el_identificador_a_la_url() {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());
        crear(pacienteConNhc(nhc));

        Bundle encontrados = buscarPorNhc(nhc);

        // Buscar sin exponer el dato no sirve de nada si el servidor contesta con un `self` que lo
        // reintroduce: el cliente lo seguiría y volvería a estar en la URL.
        assertThat(encontrados.getLink())
                .allSatisfy(enlace -> assertThat(enlace.getUrl()).doesNotContain(nhc));
    }

    private Bundle buscarPorNhc(String nhc) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> criterios = new LinkedMultiValueMap<>();
        criterios.add("identifier", SistemasDeIdentificador.NHC + "|" + nhc);

        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/Patient/_search", HttpMethod.POST, new HttpEntity<>(criterios, cabeceras), String.class);

        assertThat(respuesta.getStatusCode())
                .as("la búsqueda por POST falló: %s", respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
    }

    private void crear(Patient paciente) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(paciente);

        ResponseEntity<String> respuesta =
                rest.exchange("/fhir/Patient", HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
        assertThat(respuesta.getStatusCode())
                .as("falló el alta del paciente: %s", respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private static Patient pacienteConNhc(String nhc) {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc);
        paciente.addName(new HumanName().setFamily("Muñoz de la Torre Álvarez").addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        return paciente;
    }
}
