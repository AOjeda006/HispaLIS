package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Organization;
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
 * Invariante 3 del proyecto: <strong>un solo camino de escritura</strong>.
 *
 * <p>Todo lo que entra pasa por la API FHIR y, si tiene agregado, por el núcleo de dominio. Los
 * {@code ResourceProvider} propios lo garantizan para {@code POST /fhir/[tipo]}… y hay una segunda
 * puerta que no pasa por ellos: un {@code Bundle} de tipo {@code transaction} enviado a la raíz. El
 * procesador de transacciones de HAPI escribe <strong>llamando a las DAO directamente</strong>, así
 * que un {@code ServiceRequest} metido ahí aparecería en la proyección sin agregado, sin invariantes
 * y sin fila en el esquema {@code dominio}.
 *
 * <p>No es un fallo que se note: el recurso se lee luego tan campante por la API. Se nota el día que
 * alguien cuenta las peticiones del dominio y no cuadran con las publicadas.
 *
 * <p>Lo que <strong>sí</strong> se admite es una transacción de datos maestros —{@code Organization},
 * {@code Practitioner}—, que no tienen agregado (§10) y entran por el proveedor estándar de HAPI:
 * cerrar la puerta entera sería prohibir una interacción legítima de FHIR por si acaso.
 */
class EscrituraSoloPorElNucleoTest extends TestDeIntegracion {

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(90_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void una_transaccion_no_puede_colar_un_recurso_con_agregado() {
        Bundle transaccion = transaccionCon(pacienteDePrueba());

        ResponseEntity<String> respuesta = enviarALaRaiz(transaccion);

        assertThat(respuesta.getStatusCode().is2xxSuccessful())
                .as("una transacción que escribe saltándose el núcleo no puede dar 2xx: %s", respuesta.getBody())
                .isFalse();
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        OperationOutcome fallo = contexto.newJsonParser().parseResource(OperationOutcome.class, respuesta.getBody());
        assertThat(fallo.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(fallo.getIssueFirstRep().getDiagnostics()).contains("Patient");
    }

    @Test
    void y_tampoco_deja_el_recurso_a_medias_en_la_proyeccion() {
        // Que responda con un error no basta: si la transacción llegó a escribir antes de que nadie
        // la parase, el recurso está publicado y el dominio no se ha enterado. Así es exactamente
        // como fallaba antes — devolvía `201 Created` con `Patient/1001`, un id numérico de HAPI que
        // ni siquiera es el UUID de ningún agregado.
        Patient paciente = pacienteDePrueba();
        String nhc = paciente.getIdentifierFirstRep().getValue();
        Long antes = pacientesEnElDominio();

        enviarALaRaiz(transaccionCon(paciente));

        assertThat(pacientesEnElDominio()).isEqualTo(antes);
        assertThat(buscar("Patient?identifier=" + SistemasDeIdentificador.NHC + "|" + nhc)
                        .getTotal())
                .as("el paciente no puede quedar publicado sin su agregado")
                .isZero();
    }

    @Test
    void un_lote_tampoco() {
        // `batch` es lo mismo sin atomicidad: si valiera, la puerta seguiría abierta.
        Bundle lote = transaccionCon(pacienteDePrueba());
        lote.setType(Bundle.BundleType.BATCH);

        assertThat(enviarALaRaiz(lote).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void una_transaccion_de_datos_maestros_si_se_admite() {
        // `Organization` no tiene agregado: es dato maestro del laboratorio (§10) y entra por el
        // proveedor estándar. Prohibir la transacción entera sería vetar una interacción legítima
        // de FHIR por si acaso.
        Organization laboratorio = new Organization();
        laboratorio
                .addIdentifier()
                .setSystem("https://aojeda006.github.io/HispaLIS/sid/nica")
                .setValue("NICA" + SIGUIENTE.incrementAndGet());
        laboratorio.setName("Laboratorio HispaLIS");

        ResponseEntity<String> respuesta = enviarALaRaiz(transaccionCon(laboratorio));

        assertThat(respuesta.getStatusCode())
                .as("la transacción de datos maestros falló: %s", respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private Long pacientesEnElDominio() {
        return jdbc.queryForObject("SELECT count(*) FROM dominio.paciente", Long.class);
    }

    private Bundle buscar(String consulta) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + consulta, String.class);
        return contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
    }

    private static Bundle transaccionCon(IBaseResource recurso) {
        Bundle transaccion = new Bundle();
        transaccion.setType(Bundle.BundleType.TRANSACTION);
        transaccion
                .addEntry()
                .setResource((org.hl7.fhir.r5.model.Resource) recurso)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.POST)
                .setUrl(recurso.fhirType());
        return transaccion;
    }

    private ResponseEntity<String> enviarALaRaiz(Bundle transaccion) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(transaccion);

        return rest.exchange("/fhir", HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private static Patient pacienteDePrueba() {
        Patient paciente = new Patient();
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.NHC)
                .setValue(String.valueOf(SIGUIENTE.incrementAndGet()));
        paciente.addName(new HumanName().setFamily("Muñoz Peña").addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        return paciente;
    }
}
