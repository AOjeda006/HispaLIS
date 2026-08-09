package es.hispalis.backend.fhir.resultado;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.dominio.resultado.NoSeSabeSiEsCritico;
import es.hispalis.backend.dominio.resultado.ReglaRefleja;
import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.FacultativaDePrueba;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.math.BigDecimal;
import java.util.Optional;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * La doble validación del resultado crítico: la otra mitad del invariante de §10.
 *
 * <p>Un potasio de 6,9 mmol/L no es «un valor alto»: es una cifra por la que se llama por teléfono
 * antes de que el informe salga. Una sola firma sobre algo así es exactamente el punto donde un
 * laboratorio se equivoca de la forma más cara — y la respuesta clásica del oficio, la que este test
 * codifica, es que <strong>lo mire otra persona</strong>.
 *
 * <p>Lo que se comprueba aquí es que la segunda firma es <strong>de otro facultativo</strong>. La
 * misma persona firmando dos veces no es una revisión independiente: es la misma revisión contada
 * dos veces, y aceptarla convertiría el invariante en un contador.
 *
 * <p>La terminología es un doble <strong>solo aquí</strong>, con la forma exacta de lo que contesta
 * el {@code $lookup} de la guía para el potasio. Que el umbral se lea de verdad del catálogo lo
 * prueba {@code TerminologiaEnLaProyeccionTest} contra un HAPI real.
 */
// La terminología de esta clase es la de abajo, no el doble compartido de `TestDeIntegracion`:
// dos beans `@Primary` del mismo tipo no conviven. Ver `TerminologiaDeLosTests`.
@TestPropertySource(properties = "hispalis.test.terminologia=propia")
@Import(DobleValidacionTest.ConLosUmbralesDelCatalogo.class)
class DobleValidacionTest extends TestDeIntegracion {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(31_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("un crítico con una sola firma no llega a publicable")
    void un_critico_con_una_sola_firma_no_es_publicable() {
        String resultado = potasioDe("6.9");

        ResponseEntity<String> primera = validar(resultado, FacultativaDePrueba.REFERENCIA);

        assertThat(primera.getStatusCode())
                .as("firmar el primero no es un error: lo que pasa es que todavía no basta. Cuerpo: %s",
                        primera.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(leerResultado(resultado).getStatus())
                .as("con una firma el resultado sigue sin ser definitivo: falta la segunda")
                .isEqualTo(Enumerations.ObservationStatus.PRELIMINARY);
    }

    @Test
    @DisplayName("y por tanto tampoco entra en un informe")
    void un_critico_con_una_sola_firma_no_entra_en_un_informe() {
        String laboratorio = crear(laboratorio());
        String resultado = potasioDe("6.9");
        validar(resultado, FacultativaDePrueba.REFERENCIA);

        ResponseEntity<String> intento = enviar(informe(pacienteDe(resultado), laboratorio, resultado));

        assertThat(intento.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /**
     * El caso que da sentido a todo lo demás.
     *
     * <p>Si la misma persona pudiera poner las dos firmas, la regla no diría «que lo mire otro»
     * diría «que lo mires dos veces», y eso no detecta nada: quien se equivocó al leer la cifra se
     * vuelve a equivocar igual treinta segundos después.
     */
    @Test
    @DisplayName("el mismo facultativo firmando dos veces no es una doble validación")
    void el_mismo_facultativo_no_puede_poner_las_dos_firmas() {
        String resultado = potasioDe("6.9");
        validar(resultado, FacultativaDePrueba.REFERENCIA);

        ResponseEntity<String> repetida = validar(resultado, FacultativaDePrueba.REFERENCIA);

        assertThat(repetida.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(diagnostico(repetida))
                .as("y el mensaje tiene que decir POR QUÉ, o quien lo lea vuelve a intentarlo igual")
                .containsIgnoringCase("otro facultativo");
        assertThat(leerResultado(resultado).getStatus()).isEqualTo(Enumerations.ObservationStatus.PRELIMINARY);
    }

    @Test
    @DisplayName("dos facultativos distintos sí cierran la validación, con una procedencia cada uno")
    void dos_facultativos_distintos_cierran_la_validacion() {
        String resultado = potasioDe("6.9");

        validar(resultado, FacultativaDePrueba.REFERENCIA);
        ResponseEntity<String> segunda = validar(resultado, FacultativaDePrueba.SEGUNDA_REFERENCIA);

        assertThat(segunda.getStatusCode())
                .as("cuerpo: %s", segunda.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(leerResultado(resultado).getStatus()).isEqualTo(Enumerations.ObservationStatus.FINAL);
        assertThat(procedenciasDe(resultado))
                .as("cada firma da fe de un acto distinto, así que cada una tiene su propia procedencia")
                .hasSize(2)
                .extracting(procedencia ->
                        procedencia.getAgentFirstRep().getWho().getReference())
                .containsExactlyInAnyOrder(FacultativaDePrueba.REFERENCIA, FacultativaDePrueba.SEGUNDA_REFERENCIA);
    }

    @Test
    @DisplayName("cerrada la doble validación, una tercera firma ya no cabe")
    void una_tercera_firma_no_cabe() {
        String resultado = potasioDe("6.9");
        validar(resultado, FacultativaDePrueba.REFERENCIA);
        validar(resultado, FacultativaDePrueba.SEGUNDA_REFERENCIA);

        ResponseEntity<String> tercera = validar(resultado, "Practitioner/tercera");

        assertThat(tercera.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(diagnostico(tercera)).containsIgnoringCase("ya está validado");
    }

    /** Control negativo: sin él, «exige siempre dos firmas» aprobaría todo lo de arriba. */
    @Test
    @DisplayName("un potasio que no alcanza el umbral se valida con una sola firma")
    void lo_que_no_es_critico_sigue_validandose_con_una_firma() {
        String resultado = potasioDe("4.3");

        validar(resultado, FacultativaDePrueba.REFERENCIA);

        assertThat(leerResultado(resultado).getStatus()).isEqualTo(Enumerations.ObservationStatus.FINAL);
        assertThat(procedenciasDe(resultado)).hasSize(1);
    }

    /**
     * Y si no se puede saber si es crítico, no se valida.
     *
     * <p>Es la decisión que el ítem 43 dejó escrita para quien enchufase esto: contestar «no es
     * crítico» a una pregunta sin respuesta es la única forma de fallar que el catálogo de críticos
     * existe para evitar. Un {@code 503} dice la verdad —el laboratorio no puede ahora mismo— y el
     * cliente sabe que tiene que reintentar; un {@code 200} habría publicado como definitivo un
     * resultado que quizá exigía una llamada de teléfono.
     */
    @Test
    @DisplayName("si el catálogo no contesta, el laboratorio no valida: 503, no un final silencioso")
    void sin_saber_si_es_critico_no_se_valida() {
        String resultado = sodio();

        ResponseEntity<String> intento = validar(resultado, FacultativaDePrueba.REFERENCIA);

        assertThat(intento.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(leerResultado(resultado).getStatus()).isEqualTo(Enumerations.ObservationStatus.PRELIMINARY);
    }

    // ─── Andamiaje ──────────────────────────────────────────────────────────

    private String potasioDe(String valor) {
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));
        return crear(resultado(paciente, muestra, "K", new BigDecimal(valor), "mmol/L"));
    }

    private String sodio() {
        String paciente = crear(paciente());
        String muestra = crear(muestra(paciente));
        return crear(resultado(paciente, muestra, "NA", new BigDecimal("141"), "mmol/L"));
    }

    private String pacienteDe(String resultado) {
        return leerResultado(resultado).getSubject().getReference();
    }

    private ResponseEntity<String> validar(String resultado, String facultativa) {
        FacultativaDePrueba.darDeAlta(rest, contexto, FacultativaDePrueba.REFERENCIA);
        FacultativaDePrueba.darDeAlta(rest, contexto, FacultativaDePrueba.SEGUNDA_REFERENCIA);

        Parameters parametros = new Parameters();
        parametros.addParameter().setName("facultativo").setValue(new Reference(facultativa));
        return rest.exchange(
                "/fhir/" + resultado + "/$validar", HttpMethod.POST, nuevaPeticion(parametros), String.class);
    }

    private java.util.List<Provenance> procedenciasDe(String resultado) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/Provenance?target=" + resultado, String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);

        Bundle encontrado = contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
        return encontrado.getEntry().stream()
                .map(entrada -> (Provenance) entrada.getResource())
                .toList();
    }

    private Observation leerResultado(String referencia) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + referencia, String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Observation.class, respuesta.getBody());
    }

    private String diagnostico(ResponseEntity<String> respuesta) {
        OperationOutcome fallo = contexto.newJsonParser().parseResource(OperationOutcome.class, respuesta.getBody());
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
        paciente.addName(new HumanName().setFamily("Muñoz Peñalver").addGiven("Begoña"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
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

    private static Observation resultado(
            String paciente, String muestra, String prueba, BigDecimal valor, String unidad) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.PRELIMINARY);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(prueba)));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(muestra));
        resultado.setValue(new Quantity()
                .setValue(valor)
                .setUnit(unidad)
                .setSystem(UCUM)
                .setCode(unidad));
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

    /**
     * Una terminología que contesta por el potasio lo que contestaría la guía, y que por el sodio no
     * contesta.
     *
     * <p>Las dos respuestas hacen falta y son distintas: el potasio prueba la regla y el sodio prueba
     * qué pasa cuando la pregunta se queda sin responder, que es el caso que el ítem 43 dejó abierto.
     */
    @TestConfiguration
    static class ConLosUmbralesDelCatalogo {

        private static final String PROCEDENCIA =
                "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182).";

        @Bean
        @Primary
        Terminologia terminologiaConUmbrales() {
            return new Terminologia() {

                @Override
                public CodeableConcept pruebaDelCatalogo(String codigoLocal) {
                    return new CodeableConcept()
                            .addCoding(new Coding()
                                    .setSystem(CatalogoDePruebas.SYSTEM)
                                    .setCode(codigoLocal));
                }

                @Override
                public void exigirQueLaPruebaExiste(String codigoLocal) {
                    // Sin autoridad a la que preguntar, rechazar sería inventarse la respuesta.
                }

                @Override
                public Optional<UmbralCritico> umbralDe(String codigoDePrueba) {
                    if ("NA".equals(codigoDePrueba)) {
                        throw new NoSeSabeSiEsCritico(
                                "El servidor de terminología no ha contestado por «NA», así que no se sabe si "
                                        + "tiene umbral crítico.");
                    }
                    return "K".equals(codigoDePrueba)
                            ? Optional.of(new UmbralCritico(
                                    "K", new BigDecimal("2.8"), new BigDecimal("6.3"), "mmol/L", PROCEDENCIA))
                            : Optional.empty();
                }

                @Override
                public Optional<ReglaRefleja> reflejaDe(String codigoDePrueba) {
                    return Optional.empty();
                }
            };
        }
    }
}
