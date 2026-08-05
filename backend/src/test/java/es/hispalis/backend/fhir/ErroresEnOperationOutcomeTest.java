package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Specimen;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Criterio de aceptación 9 (§14 del diseño): todo error sale como {@code OperationOutcome} y con el
 * código HTTP que le toca.
 *
 * <p>Un cliente FHIR —la web del laboratorio, el motor de integración, el hospital de enfrente— no
 * lee prosa: <strong>decide por el código de estado</strong>. Con un {@code 400} deja de reintentar
 * porque lo que manda está mal; con un {@code 409} relee y vuelve; con un {@code 412} sabe que otro
 * escribió antes; con un {@code 422} entiende que lo enviado es correcto pero la acción no procede.
 * Devolver todo como {@code 500} —o peor, como {@code 200} con el error dentro— convierte cualquiera
 * de esas decisiones en una moneda al aire.
 *
 * <p>Cada caso comprueba <strong>las tres cosas a la vez</strong>: el código, que el cuerpo sea un
 * {@code OperationOutcome} de verdad, y que traiga un diagnóstico que se entienda sin llamar por
 * teléfono. Un {@code 422} con el cuerpo vacío obliga al que lo recibe a adivinar qué invariante
 * incumplió.
 *
 * <p>La correspondencia la fija {@link TraduccionDeErroresDeDominio}; aquí se prueba desde fuera, por
 * la API, que es como la ve un cliente.
 */
class ErroresEnOperationOutcomeTest extends TestDeIntegracion {

    private static final AtomicInteger SIGUIENTE_NHC = new AtomicInteger(60_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void un_cuerpo_que_ni_siquiera_es_json_devuelve_400() {
        ResponseEntity<String> respuesta = enviarCrudo("/fhir/Patient", "{\"resourceType\": \"Patient\", \"gender\":");

        OperationOutcome fallo = exigirFallo(respuesta, HttpStatus.BAD_REQUEST);
        assertThat(fallo.getIssueFirstRep().getDiagnostics()).isNotBlank();
    }

    @Test
    void un_paciente_sin_numero_de_historia_devuelve_400() {
        Patient sinNhc = new Patient();
        sinNhc.addName(new HumanName().setFamily("Muñoz Álvarez").addGiven("Begoña"));
        sinNhc.setGender(Enumerations.AdministrativeGender.FEMALE);

        // Bien formado como FHIR y aun así inaceptable: el laboratorio no sabe de quién es la muestra.
        OperationOutcome fallo = exigirFallo(enviar(sinNhc), HttpStatus.BAD_REQUEST);
        assertThat(fallo.getIssueFirstRep().getDiagnostics()).containsIgnoringCase("historia clínica");
    }

    @Test
    void un_recurso_que_no_existe_devuelve_404() {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/Patient/" + UUID.randomUUID(), String.class);

        exigirFallo(respuesta, HttpStatus.NOT_FOUND);
    }

    @Test
    void repetir_un_numero_de_historia_devuelve_409() {
        String nhc = nuevoNhc();
        crear(pacienteDePrueba(nhc));

        // No es un dato mal formado —es correcto— sino que choca con algo que ya existe: son dos
        // situaciones distintas y el cliente actúa distinto ante cada una.
        OperationOutcome fallo = exigirFallo(enviar(pacienteDePrueba(nhc)), HttpStatus.CONFLICT);
        assertThat(fallo.getIssueFirstRep().getDiagnostics()).contains(nhc);
    }

    @Test
    void un_if_match_de_una_version_que_no_es_la_vigente_devuelve_412() {
        String nhc = nuevoNhc();
        String referencia = crear(pacienteDePrueba(nhc));

        Patient corregido = pacienteDePrueba(nhc);
        corregido.setId(referencia);

        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        cabeceras.setIfMatch("W/\"9\"");
        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + referencia,
                HttpMethod.PUT,
                new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(corregido), cabeceras),
                String.class);

        exigirFallo(respuesta, HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void un_invariante_de_negocio_incumplido_devuelve_422() {
        String paciente = crear(pacienteDePrueba(nuevoNhc()));
        String laboratorio = crear(laboratorioDePrueba());

        DiagnosticReport vacio = new DiagnosticReport();
        vacio.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        vacio.setSubject(new Reference(paciente));
        vacio.addPerformer(new Reference(laboratorio));

        // El recurso es válido para el estándar: `result` es 0..*. Lo que no vale es en este
        // laboratorio, y por eso son 422 y no 400 — la sintaxis está bien, la acción no procede.
        OperationOutcome fallo = exigirFallo(enviar(vacio), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(fallo.getIssueFirstRep().getDiagnostics()).isNotBlank();
    }

    @Test
    void modificar_una_muestra_ya_registrada_se_rechaza_con_422_y_no_con_500() {
        String paciente = crear(pacienteDePrueba(nuevoNhc()));
        String muestra = crear(muestraDePrueba(paciente));

        Specimen corregida = muestraDePrueba(paciente);
        corregida.setId(muestra);

        // El `PUT` de los recursos cuya modificación aún no pasa por el dominio se rechaza a
        // propósito (`EscrituraSoloPorAlta`). Ese rechazo tiene que llegar al cliente como lo que es
        // —una regla del laboratorio— y no como un fallo del servidor: es un camino de escritura que
        // no lo recorre ningún otro test, y si la traducción de errores no lo alcanzara saldría un
        // 500 sin que nadie se enterase.
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + muestra,
                HttpMethod.PUT,
                new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(corregida), cabeceras),
                String.class);

        OperationOutcome fallo = exigirFallo(respuesta, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(fallo.getIssueFirstRep().getDiagnostics())
                .as("hay que decir qué pasa, no solo negarse")
                .containsIgnoringCase("no está soportado");
    }

    /**
     * Comprueba las tres cosas que hacen útil a un error: el código, la forma y la explicación.
     *
     * <p>La primera es <strong>que no sea un {@code 2xx}</strong>. Suena redundante teniendo el código
     * exacto justo debajo, pero es el fallo que este criterio persigue: un servidor que responde
     * {@code 200} con un {@code OperationOutcome} de errores dentro tiene toda la apariencia de haber
     * funcionado, y un cliente que mira el código —todos lo miran— da el alta por buena.
     */
    private OperationOutcome exigirFallo(ResponseEntity<String> respuesta, HttpStatusCode esperado) {
        assertThat(respuesta.getStatusCode().is2xxSuccessful())
                .as("un error disfrazado de éxito: %s", respuesta.getBody())
                .isFalse();
        assertThat(respuesta.getStatusCode()).isEqualTo(esperado);

        // Se mira el tipo y se ignora el `charset`: lo que importa es que el cliente sepa que puede
        // parsearlo como FHIR. Un error en texto plano obliga a leerlo con los ojos.
        MediaType tipo = respuesta.getHeaders().getContentType();
        assertThat(tipo).isNotNull();
        assertThat(tipo.getType() + "/" + tipo.getSubtype()).isEqualTo("application/fhir+json");

        OperationOutcome fallo = contexto.newJsonParser().parseResource(OperationOutcome.class, respuesta.getBody());
        assertThat(fallo.getIssue()).isNotEmpty();
        assertThat(fallo.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        return fallo;
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
        return enviarCrudo(
                "/fhir/" + recurso.fhirType(), contexto.newJsonParser().encodeResourceToString(recurso));
    }

    private ResponseEntity<String> enviarCrudo(String ruta, String cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        return rest.exchange(ruta, HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private static String nuevoNhc() {
        return String.valueOf(SIGUIENTE_NHC.incrementAndGet());
    }

    private static Patient pacienteDePrueba(String nhc) {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc);
        paciente.addName(new HumanName().setFamily("Peña Muñoz").addGiven("Álvaro"));
        paciente.setGender(Enumerations.AdministrativeGender.MALE);
        return paciente;
    }

    private static Specimen muestraDePrueba(String paciente) {
        Specimen muestra = new Specimen();
        muestra.getAccessionIdentifier().setValue("A" + SIGUIENTE_NHC.incrementAndGet());
        muestra.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        muestra.setType(new CodeableConcept()
                .addCoding(new Coding().setSystem("http://snomed.info/sct").setCode("122555007")));
        muestra.setSubject(new Reference(paciente));
        return muestra;
    }

    private static Organization laboratorioDePrueba() {
        Organization laboratorio = new Organization();
        laboratorio
                .addIdentifier()
                .setSystem("https://aojeda006.github.io/HispaLIS/sid/nica")
                .setValue("NICA" + SIGUIENTE_NHC.incrementAndGet());
        laboratorio.setName("Laboratorio HispaLIS");
        return laboratorio;
    }
}
