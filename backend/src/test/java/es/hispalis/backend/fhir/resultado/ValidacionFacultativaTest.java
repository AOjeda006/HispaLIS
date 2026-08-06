package es.hispalis.backend.fhir.resultado;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Provenance;
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
 * La validación facultativa: el paso que convierte una medida en un resultado publicable.
 *
 * <p>Lo que sale de un analizador <strong>no es un resultado</strong>, es una cifra. Entre la cifra y
 * el informe hay una persona que la mira, la contrasta con la clínica y con los controles del día, y
 * responde de ella. Sin ese paso el laboratorio publica lo que diga la máquina, incluida la avería
 * del reactivo de esta mañana.
 *
 * <p>De aquí cuelgan dos cosas del proyecto que todavía no existen: el {@code ORU^R01} saliente
 * («cuando el informe se valida») y todo el notificador EDO del hito 3 («cuando un resultado
 * <em>validado</em> cae en el catálogo de declaración obligatoria»). Sin estado de validación las
 * dos tendrían que inventarse uno.
 *
 * <p>La trazabilidad —quién validó y cuándo— va en {@code Provenance}, que §6.1 del diseño ya mapea
 * como recurso aparte. No hace falta extensión ninguna.
 */
class ValidacionFacultativaTest extends TestDeIntegracion {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";
    private static final String FACULTATIVA = "Practitioner/analisis-clinicos";

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(25_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void un_resultado_recien_informado_sale_preliminar() {
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));

        String resultado = crear(resultado(paciente, muestra, 92));

        assertThat(leerResultado(resultado).getStatus())
                .as("el analizador mide, no valida: publicarlo como final es firmar por él")
                .isEqualTo(Enumerations.ObservationStatus.PRELIMINARY);
    }

    @Test
    void un_informe_no_se_emite_con_un_resultado_sin_validar() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));
        String resultado = crear(resultado(paciente, muestra, 92));

        ResponseEntity<String> intento = enviar(informe(paciente, laboratorio, resultado));

        assertThat(intento.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(diagnostico(intento)).containsIgnoringCase("valid");
    }

    @Test
    void validar_deja_el_resultado_final_y_su_procedencia_publicada() {
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));
        String resultado = crear(resultado(paciente, muestra, 92));

        ResponseEntity<String> validado = validar(resultado, FACULTATIVA);
        assertThat(validado.getStatusCode())
                .as("cuerpo: %s", validado.getBody())
                .isEqualTo(HttpStatus.OK);

        assertThat(leerResultado(resultado).getStatus()).isEqualTo(Enumerations.ObservationStatus.FINAL);

        Provenance procedencia = procedenciaDe(resultado);
        assertThat(procedencia.getTargetFirstRep().getReference()).isEqualTo(resultado);
        assertThat(procedencia.getAgentFirstRep().getWho().getReference())
                .as("quién responde del resultado es la mitad de para qué existe la validación")
                .isEqualTo(FACULTATIVA);
        assertThat(procedencia.hasRecorded()).isTrue();
    }

    /** Control positivo: sin él, «rechaza siempre» aprobaría el test del informe sin validar. */
    @Test
    void con_el_resultado_validado_el_informe_sale() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));
        String resultado = crear(resultado(paciente, muestra, 92));

        validar(resultado, FACULTATIVA);

        ResponseEntity<String> emitido = enviar(informe(paciente, laboratorio, resultado));
        assertThat(emitido.getStatusCode()).as("cuerpo: %s", emitido.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * Revalidar no es corregir. Si valiera, la segunda firma taparía a la primera y el rastro de
     * quién respondió del resultado quedaría reescrito sin dejar constancia.
     */
    @Test
    void un_resultado_no_se_valida_dos_veces() {
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));
        String resultado = crear(resultado(paciente, muestra, 92));

        validar(resultado, FACULTATIVA);
        ResponseEntity<String> segunda = validar(resultado, "Practitioner/otra");

        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(diagnostico(segunda)).containsIgnoringCase("ya está validado");
    }

    @Test
    void validar_sin_decir_quien_valida_no_vale() {
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));
        String resultado = crear(resultado(paciente, muestra, 92));

        ResponseEntity<String> intento = validar(resultado, null);

        assertThat(intento.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(diagnostico(intento)).containsIgnoringCase("quién");
    }

    private ResponseEntity<String> validar(String resultado, String facultativa) {
        Parameters parametros = new Parameters();
        if (facultativa != null) {
            parametros.addParameter().setName("facultativo").setValue(new Reference(facultativa));
        }
        return rest.exchange(
                "/fhir/" + resultado + "/$validar", HttpMethod.POST, nuevaPeticion(parametros), String.class);
    }

    private Provenance procedenciaDe(String resultado) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/Provenance?target=" + resultado, String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);

        Bundle encontrado = contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
        assertThat(encontrado.getEntry())
                .as("la validación tiene que dejar rastro consultable, no solo un estado")
                .hasSize(1);
        return (Provenance) encontrado.getEntryFirstRep().getResource();
    }

    private Observation leerResultado(String referencia) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + referencia, String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Observation.class, respuesta.getBody());
    }

    private String diagnostico(ResponseEntity<String> respuesta) {
        OperationOutcome fallo = contexto.newJsonParser().parseResource(OperationOutcome.class, respuesta.getBody());
        assertThat(fallo.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        return fallo.getIssueFirstRep().getDiagnostics();
    }

    private String crear(IBaseResource recurso) {
        ResponseEntity<String> respuesta = enviar(recurso);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo preparar el escenario con %s: %s", recurso.fhirType(), respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String location = respuesta.getHeaders().getFirst(HttpHeaders.LOCATION);
        return location.substring(0, location.indexOf("/_history")).substring(location.indexOf("/fhir/") + 6);
    }

    private ResponseEntity<String> enviar(IBaseResource recurso) {
        return rest.exchange("/fhir/" + recurso.fhirType(), HttpMethod.POST, nuevaPeticion(recurso), String.class);
    }

    private HttpEntity<String> nuevaPeticion(IBaseResource recurso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        return new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(recurso), cabeceras);
    }

    private static Organization laboratorio() {
        Organization laboratorio = new Organization();
        laboratorio
                .addIdentifier()
                .setSystem("https://aojeda006.github.io/HispaLIS/sid/nica")
                .setValue("NICA" + SIGUIENTE.incrementAndGet());
        laboratorio.setName("Laboratorio HispaLIS");
        return laboratorio;
    }

    private static Patient paciente() {
        Patient paciente = new Patient();
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.NHC)
                .setValue(String.valueOf(SIGUIENTE.incrementAndGet()));
        paciente.addName(new HumanName().setFamily("Peña Álvarez").addGiven("Íñigo"));
        paciente.setGender(Enumerations.AdministrativeGender.MALE);
        return paciente;
    }

    private static Specimen muestra(String paciente) {
        Specimen especimen = new Specimen();
        especimen.getAccessionIdentifier().setValue("A" + SIGUIENTE.incrementAndGet());
        especimen.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        especimen.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(SANGRE_VENOSA)));
        especimen.setSubject(new Reference(paciente));
        return especimen;
    }

    private static Observation resultado(String paciente, String muestra, double valor) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.FINAL);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("GLU")));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(muestra));
        resultado.setValue(
                new Quantity().setValue(valor).setUnit("mg/dL").setSystem(UCUM).setCode("mg/dL"));
        return resultado;
    }

    private static DiagnosticReport informe(String paciente, String emisor, String... resultados) {
        DiagnosticReport informe = new DiagnosticReport();
        informe.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        informe.setSubject(new Reference(paciente));
        informe.addPerformer(new Reference(emisor));
        for (String resultado : resultados) {
            informe.addResult(new Reference(resultado));
        }
        return informe;
    }
}
