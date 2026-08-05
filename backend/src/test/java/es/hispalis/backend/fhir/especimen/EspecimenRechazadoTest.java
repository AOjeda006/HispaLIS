package es.hispalis.backend.fhir.especimen;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.OperationOutcome;
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

/**
 * Criterio de aceptación 6 (§14 del diseño): una muestra rechazada no puede producir un resultado.
 *
 * <p>Es el invariante que <strong>FHIR no puede expresar</strong>. Nada en el estándar impide crear
 * un {@code Observation} que apunte a un {@code Specimen} con {@code status = unsatisfactory}: los
 * dos recursos son válidos por separado y la referencia entre ellos también. Lo que está mal es la
 * <em>combinación</em>, y eso solo lo sabe el laboratorio.
 *
 * <p>Y no es un tecnicismo: informar un resultado de una muestra hemolizada, coagulada o mal
 * conservada es emitir un dato clínico falso con toda la apariencia de ser bueno.
 *
 * <p>El invariante vive en el <strong>núcleo de dominio</strong>, no en el {@code ResourceProvider}:
 * tiene que valer igual venga la petición de la web, del motor de integración o de un script.
 */
class EspecimenRechazadoTest extends TestDeIntegracion {

    private static final String SYSTEM_NHC = "https://aojeda006.github.io/HispaLIS/sid/nhc";
    private static final String SYSTEM_ACCESO = "https://aojeda006.github.io/HispaLIS/sid/acceso";
    private static final String CATALOGO = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas";
    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";

    /** Sangre venosa. */
    private static final String TIPO_SANGRE = "122555007";

    /** Muestra hemolizada, de la tabla v2-0493 a la que R5 ata `Specimen.condition`. */
    private static final String CONDICION_HEMOLIZADA = "http://terminology.hl7.org/CodeSystem/v2-0493";

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(20_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void un_especimen_rechazado_no_puede_producir_un_resultado() {
        String paciente = crear(pacienteDePrueba());
        String rechazado = crear(especimenDePrueba(paciente, Specimen.SpecimenStatus.UNSATISFACTORY));

        ResponseEntity<String> intento = enviar(resultadoDeGlucosa(paciente, rechazado));

        assertThat(intento.getStatusCode())
                .as("informar el resultado de una muestra rechazada es emitir un dato clínico falso")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        OperationOutcome resultado = contexto.newJsonParser().parseResource(OperationOutcome.class, intento.getBody());
        assertThat(resultado.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(resultado.getIssueFirstRep().getDiagnostics())
                .as("quien recibe el error tiene que entender por qué sin llamar por teléfono")
                .containsIgnoringCase("rechazad");
    }

    /**
     * Control positivo. Sin él, un invariante implementado como «rechaza siempre» pasaría el test de
     * arriba y rompería el laboratorio entero.
     */
    @Test
    void un_especimen_valido_si_puede_producir_un_resultado() {
        String paciente = crear(pacienteDePrueba());
        String valido = crear(especimenDePrueba(paciente, Specimen.SpecimenStatus.AVAILABLE));

        ResponseEntity<String> aceptado = enviar(resultadoDeGlucosa(paciente, valido));

        assertThat(aceptado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Devuelve la referencia relativa ({@code Patient/123}) del recurso recién creado. */
    private String crear(IBaseResource recurso) {
        ResponseEntity<String> respuesta = enviar(recurso);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo preparar el escenario: %s", respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String location = respuesta.getHeaders().getFirst(HttpHeaders.LOCATION);
        String sinHistoria = location.substring(0, location.indexOf("/_history"));
        return sinHistoria.substring(sinHistoria.indexOf("/fhir/") + "/fhir/".length());
    }

    private ResponseEntity<String> enviar(IBaseResource recurso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String tipo = recurso.fhirType();
        String cuerpo = contexto.newJsonParser().encodeResourceToString(recurso);

        return rest.exchange("/fhir/" + tipo, HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
    }

    private static Patient pacienteDePrueba() {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SYSTEM_NHC).setValue(String.valueOf(SIGUIENTE.incrementAndGet()));
        paciente.addName(new HumanName().setFamily("Muñoz Peña").addGiven("Álvaro"));
        paciente.setGender(Enumerations.AdministrativeGender.MALE);
        return paciente;
    }

    private static Specimen especimenDePrueba(String paciente, Specimen.SpecimenStatus estado) {
        Specimen especimen = new Specimen();
        especimen.getAccessionIdentifier().setSystem(SYSTEM_ACCESO).setValue("A" + SIGUIENTE.incrementAndGet());
        especimen.setStatus(estado);
        especimen.setType(new CodeableConcept()
                .addCoding(new org.hl7.fhir.r5.model.Coding().setSystem(SNOMED).setCode(TIPO_SANGRE)));
        especimen.setSubject(new Reference(paciente));

        // `hlis-esp-1`: un rechazo sin motivo obliga al peticionario a llamar por teléfono.
        if (estado == Specimen.SpecimenStatus.UNSATISFACTORY) {
            especimen.addCondition(new CodeableConcept()
                    .addCoding(new org.hl7.fhir.r5.model.Coding()
                            .setSystem(CONDICION_HEMOLIZADA)
                            .setCode("HEM")));
        }
        return especimen;
    }

    private static Observation resultadoDeGlucosa(String paciente, String especimen) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.FINAL);
        resultado.setCode(new CodeableConcept()
                .addCoding(
                        new org.hl7.fhir.r5.model.Coding().setSystem(CATALOGO).setCode("GLU")));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(especimen));
        resultado.setValue(
                new Quantity().setValue(92).setUnit("mg/dL").setSystem(UCUM).setCode("mg/dL"));
        return resultado;
    }
}
