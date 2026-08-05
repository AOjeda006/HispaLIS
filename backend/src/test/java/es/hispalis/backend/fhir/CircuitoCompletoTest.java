package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.ServiceRequest;
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

/**
 * Criterio de aceptación 5 (§14 del diseño): el circuito básico, de extremo a extremo, por la API.
 *
 * <p>Recorre <strong>paciente → petición → espécimen → resultado → informe</strong> contra el
 * servidor real, sin atajos: cada paso usa el {@code Location} del anterior, así que si un eslabón
 * no publica lo que dice, el siguiente no encuentra a qué apuntar.
 *
 * <p>Al terminar <strong>vuelca los cinco recursos a {@code target/circuito/}</strong>. No es un
 * residuo del test: es la entrada del validador oficial de HL7, que en la CI los comprueba contra
 * los perfiles de la guía. Que el circuito funcione y que lo que produce sea conforme son dos cosas
 * distintas, y la segunda solo la puede afirmar el validador de la especificación, no este test.
 */
class CircuitoCompletoTest extends TestDeIntegracion {

    /** Los recursos que produce el circuito, para que el validador oficial los revise en la CI. */
    private static final Path VOLCADO = Path.of("target", "circuito");

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(30_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void el_circuito_completo_de_peticion_a_informe() {
        // El laboratorio que firma. No tiene agregado de dominio: es dato maestro, no un agregado
        // con invariantes propios (§10), así que entra por el proveedor estándar de HAPI.
        String laboratorio = crear(laboratorioDePrueba(), "0-laboratorio");

        String paciente = crear(pacienteDePrueba(), "1-paciente");
        String peticion = crear(peticionDePrueba(paciente, laboratorio), "2-peticion");
        String especimen = crear(especimenDePrueba(paciente), "3-especimen");
        String resultado = crear(resultadoDePrueba(paciente, especimen, peticion, laboratorio), "4-resultado");
        String informe = crear(informeDePrueba(paciente, resultado, laboratorio), "5-informe");

        // Que cada recurso exista es la mitad; la otra es que las referencias estén bien puestas.
        DiagnosticReport emitido = leer(informe, DiagnosticReport.class);
        assertThat(emitido.getResult()).hasSize(1);
        assertThat(emitido.getResultFirstRep().getReference()).isEqualTo(resultado);
        assertThat(emitido.getSubject().getReference()).isEqualTo(paciente);

        Observation informado = leer(resultado, Observation.class);
        assertThat(informado.getSpecimen().getReference()).isEqualTo(especimen);
        assertThat(informado.getBasedOnFirstRep().getReference())
                .as("el resultado tiene que poder rastrearse hasta la línea de petición que lo pidió")
                .isEqualTo(peticion);
    }

    @Test
    void un_informe_sin_resultados_no_se_emite() {
        String laboratorio = crear(laboratorioDePrueba(), null);
        String paciente = crear(pacienteDePrueba(), null);

        DiagnosticReport vacio = new DiagnosticReport();
        vacio.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        vacio.setSubject(new Reference(paciente));
        vacio.addPerformer(new Reference(laboratorio));

        ResponseEntity<String> respuesta = enviar(vacio);

        assertThat(respuesta.getStatusCode())
                .as("un informe vacío llega con apariencia de respuesta y no contiene ninguna")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** Crea el recurso, comprueba que devuelve 201 y lo vuelca si se le da nombre. */
    private String crear(IBaseResource recurso, String nombreDelVolcado) {
        ResponseEntity<String> respuesta = enviar(recurso);
        assertThat(respuesta.getStatusCode())
                .as("falló al crear %s: %s", recurso.fhirType(), respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String location = respuesta.getHeaders().getFirst(HttpHeaders.LOCATION);
        String referencia = location.substring(0, location.indexOf("/_history"))
                .substring(location.indexOf("/fhir/") + "/fhir/".length());

        if (nombreDelVolcado != null) {
            volcar(nombreDelVolcado, referencia);
        }
        return referencia;
    }

    private <T extends IBaseResource> T leer(String referencia, Class<T> tipo) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + referencia, String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(tipo, respuesta.getBody());
    }

    /**
     * Guarda tal cual lo que el servidor devuelve. Se vuelca <strong>lo leído y no lo enviado</strong>
     * a propósito: lo que hay que validar es la proyección que el laboratorio publica, no lo que un
     * cliente le mandó.
     */
    private void volcar(String nombre, String referencia) {
        ResponseEntity<String> leido = rest.getForEntity("/fhir/" + referencia, String.class);
        try {
            Files.createDirectories(VOLCADO);
            Files.writeString(VOLCADO.resolve(nombre + ".json"), leido.getBody());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo volcar %s para el validador".formatted(nombre), e);
        }
    }

    private ResponseEntity<String> enviar(IBaseResource recurso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(recurso);

        return rest.exchange(
                "/fhir/" + recurso.fhirType(), HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
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

    private static Patient pacienteDePrueba() {
        Patient paciente = new Patient();
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.NHC)
                .setValue(String.valueOf(SIGUIENTE.incrementAndGet()));
        paciente.addName(new HumanName().setFamily("Peña Muñoz").addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        paciente.setBirthDateElement(new org.hl7.fhir.r5.model.DateType("1981-03-14"));
        return paciente;
    }

    private static ServiceRequest peticionDePrueba(String paciente, String solicitante) {
        ServiceRequest peticion = new ServiceRequest();
        peticion.setStatus(Enumerations.RequestStatus.ACTIVE);
        peticion.setIntent(Enumerations.RequestIntent.ORDER);
        peticion.getRequisition().setValue("P" + SIGUIENTE.incrementAndGet());
        peticion.setSubject(new Reference(paciente));
        peticion.setRequester(new Reference(solicitante));
        // R5: `code` es CodeableReference, no CodeableConcept.
        peticion.setCode(new CodeableReference(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("GLU"))));
        return peticion;
    }

    private static Specimen especimenDePrueba(String paciente) {
        Specimen especimen = new Specimen();
        especimen.getAccessionIdentifier().setValue("A" + SIGUIENTE.incrementAndGet());
        especimen.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        especimen.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(SANGRE_VENOSA)));
        especimen.setSubject(new Reference(paciente));
        return especimen;
    }

    private static Observation resultadoDePrueba(
            String paciente, String especimen, String peticion, String laboratorio) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.FINAL);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("GLU")));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(especimen));
        resultado.addBasedOn(new Reference(peticion));
        resultado.setValue(
                new Quantity().setValue(92).setUnit("mg/dL").setSystem(UCUM).setCode("mg/dL"));

        // Cuándo se midió y quién lo hizo. Van aquí y no en un test aparte porque lo que se vuelca
        // de este circuito es lo que revisa el validador oficial: si el resultado que publica el
        // laboratorio no los lleva, no están publicados aunque el servidor sepa guardarlos.
        resultado.setEffective(new DateTimeType(Date.from(Instant.now().minus(2, ChronoUnit.HOURS))));
        resultado.addPerformer(new Reference(laboratorio));
        return resultado;
    }

    private static DiagnosticReport informeDePrueba(String paciente, String resultado, String emisor) {
        DiagnosticReport informe = new DiagnosticReport();
        informe.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        informe.setSubject(new Reference(paciente));
        informe.addResult(new Reference(resultado));
        informe.addPerformer(new Reference(emisor));
        return informe;
    }
}
