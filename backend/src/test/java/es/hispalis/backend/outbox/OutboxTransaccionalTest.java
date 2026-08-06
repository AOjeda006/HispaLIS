package es.hispalis.backend.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.FacultativaDePrueba;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Organization;
import org.hl7.fhir.r5.model.Parameters;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * El {@code outbox} transaccional: los hechos que el laboratorio deja apuntados para publicar.
 *
 * <p>El bus del hito 2 no puede publicar nada que no esté escrito, y escribirlo <strong>después</strong>
 * de confirmar la transacción es perder hechos en cuanto algo se caiga entre las dos operaciones. Por
 * eso el hecho se escribe en la <strong>misma transacción</strong> que el dominio y la proyección
 * (§9): o entra todo, o no entra nada.
 *
 * <p>Se prueba <strong>por el lado del fallo</strong>. Un test del camino feliz pasaría igual con dos
 * transacciones separadas y no demostraría nada; el que sí demuestra algo es el que provoca un fallo
 * <em>después</em> de que el hecho se haya escrito y comprueba que no queda rastro.
 *
 * <p>Y lo que de verdad hay que vigilar: que el hecho <strong>no lleve PHI</strong>. El invariante 6
 * del proyecto prohíbe datos clínicos y filiativos en el bus, y el sitio donde eso se incumple es
 * aquí, construyendo la carga — no en Kafka. Un hecho es {@code { pacienteId, peticionId,
 * observationRef, … }}: referencias, y nada más.
 */
class OutboxTransaccionalTest extends TestDeIntegracion {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";

    /** Apellidos y nombre que NO pueden aparecer en ningún hecho. Con los caracteres de siempre. */
    private static final String APELLIDOS = "Muñoz Peñalver";

    private static final String NOMBRE_DE_PILA = "Begoña";
    private static final String DNI = "12345678Z";
    private static final String NUHSA = "AN0123456789";

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(35_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void cada_escritura_del_circuito_deja_su_hecho() {
        String laboratorio = crear(laboratorio());
        String nhc = siguienteNhc();
        String paciente = crear(paciente(nhc));
        String linea = crear(linea(paciente, laboratorio));
        String muestra = crear(muestra(paciente));
        String resultado = crear(resultado(paciente, muestra, linea, laboratorio));
        validar(resultado);
        crear(informe(paciente, laboratorio, resultado));

        assertThat(tiposDe(paciente))
                .as("cada paso del circuito es un hecho que alguien de fuera necesita conocer")
                .containsExactly(
                        "PACIENTE_REGISTRADO",
                        "PETICION_REGISTRADA",
                        "ESPECIMEN_REGISTRADO",
                        "RESULTADO_INFORMADO",
                        "RESULTADO_VALIDADO",
                        "INFORME_EMITIDO");
    }

    /**
     * La prueba de que es una sola transacción. Este resultado supera al dominio —la muestra está
     * disponible y la línea activa—, así que el hecho ya está escrito cuando la proyección lo rechaza
     * por apuntar a un laboratorio que no existe. Con dos transacciones, el hecho sobreviviría y el
     * bus anunciaría un resultado que no llegó a publicarse nunca.
     */
    @Test
    void un_fallo_posterior_al_hecho_no_deja_rastro_en_el_outbox() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente(siguienteNhc()));
        String linea = crear(linea(paciente, laboratorio));
        String muestra = crear(muestra(paciente));

        Observation aFantasma = resultado(paciente, muestra, linea, "Organization/no-existe");
        ResponseEntity<String> rechazado = enviar(aFantasma);

        assertThat(rechazado.getStatusCode().is2xxSuccessful())
                .as("cuerpo: %s", rechazado.getBody())
                .isFalse();
        assertThat(tiposDe(paciente))
                .as("el hecho se escribió antes del fallo: si sobrevive, no era la misma transacción")
                .doesNotContain("RESULTADO_INFORMADO");
    }

    @Test
    void un_alta_que_el_dominio_rechaza_no_deja_hecho() {
        String nhc = siguienteNhc();
        String paciente = crear(paciente(nhc));

        // El mismo NHC otra vez: el laboratorio no admite dos pacientes con el mismo número.
        ResponseEntity<String> duplicado = enviar(paciente(nhc));

        assertThat(duplicado.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tiposDe(paciente)).containsExactly("PACIENTE_REGISTRADO");
    }

    /**
     * El invariante 6, comprobado donde se incumple. Ni el nombre, ni el NHC, ni el DNI, ni el NUHSA
     * pueden estar en la carga de ningún hecho: el bus publica referencias, no historias clínicas.
     */
    @Test
    void ningun_hecho_lleva_phi() {
        String laboratorio = crear(laboratorio());
        String nhc = siguienteNhc();
        String paciente = crear(paciente(nhc));
        String linea = crear(linea(paciente, laboratorio));
        String muestra = crear(muestra(paciente));
        String resultado = crear(resultado(paciente, muestra, linea, laboratorio));
        validar(resultado);
        crear(informe(paciente, laboratorio, resultado));

        List<String> cargas = cargasDe(paciente);

        assertThat(cargas).isNotEmpty();
        assertThat(cargas)
                .as("la carga de un hecho son referencias; cualquier otra cosa es una fuga")
                .noneMatch(carga -> carga.contains(nhc)
                        || carga.contains(APELLIDOS)
                        || carga.contains(NOMBRE_DE_PILA)
                        || carga.contains(DNI)
                        || carga.contains(NUHSA));
    }

    /** La clave de partición es el paciente (§9): así todo lo suyo se consume en orden. */
    @Test
    void todos_los_hechos_de_un_paciente_comparten_clave_de_particion() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente(siguienteNhc()));
        crear(linea(paciente, laboratorio));
        crear(muestra(paciente));

        List<String> claves = jdbc.queryForList(
                "SELECT DISTINCT clave_de_particion::text FROM outbox.hecho WHERE clave_de_particion = :paciente",
                new MapSqlParameterSource("paciente", UUID.fromString(identidadDe(paciente))),
                String.class);

        assertThat(claves).containsExactly(identidadDe(paciente));
    }

    /** Nada nace publicado: el relay del ítem 30 es quien marcará la fecha. */
    @Test
    void un_hecho_recien_escrito_esta_sin_publicar() {
        String paciente = crear(paciente(siguienteNhc()));

        List<Map<String, Object>> hechos = hechosDe(paciente);

        assertThat(hechos).hasSize(1);
        assertThat(hechos.get(0).get("publicado_en")).isNull();
        assertThat(hechos.get(0).get("creado_en")).isNotNull();
    }

    private List<String> tiposDe(String paciente) {
        return hechosDe(paciente).stream()
                .map(hecho -> (String) hecho.get("tipo"))
                .toList();
    }

    private List<String> cargasDe(String paciente) {
        return hechosDe(paciente).stream()
                .map(hecho -> (String) hecho.get("carga"))
                .toList();
    }

    private List<Map<String, Object>> hechosDe(String paciente) {
        return jdbc.queryForList(
                """
                SELECT tipo, carga::text AS carga, creado_en, publicado_en
                  FROM outbox.hecho
                 WHERE clave_de_particion = :paciente
                 ORDER BY creado_en, tipo
                """,
                new MapSqlParameterSource("paciente", UUID.fromString(identidadDe(paciente))));
    }

    private void validar(String resultado) {
        FacultativaDePrueba.darDeAlta(rest, contexto);

        Parameters facultativo = new Parameters();
        facultativo.addParameter().setName("facultativo").setValue(new Reference(FacultativaDePrueba.REFERENCIA));

        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + resultado + "/$validar", HttpMethod.POST, nuevaPeticion(facultativo), String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo validar %s: %s", resultado, respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private static String identidadDe(String referencia) {
        return referencia.substring(referencia.indexOf('/') + 1);
    }

    private static String siguienteNhc() {
        return String.valueOf(SIGUIENTE.incrementAndGet());
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

    private static Patient paciente(String nhc) {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc);
        paciente.addIdentifier().setSystem(SistemasDeIdentificador.DNI_NIE).setValue(DNI);
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.CIP_AUTONOMICO)
                .setValue(NUHSA);
        paciente.addName(new HumanName().setFamily(APELLIDOS).addGiven(NOMBRE_DE_PILA));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        return paciente;
    }

    private static ServiceRequest linea(String paciente, String solicitante) {
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

    private static Specimen muestra(String paciente) {
        Specimen especimen = new Specimen();
        especimen.getAccessionIdentifier().setValue("A" + SIGUIENTE.incrementAndGet());
        especimen.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        especimen.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(SANGRE_VENOSA)));
        especimen.setSubject(new Reference(paciente));
        return especimen;
    }

    private static Observation resultado(String paciente, String muestra, String linea, String quienLoMidio) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.FINAL);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("GLU")));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(muestra));
        resultado.addBasedOn(new Reference(linea));
        resultado.addPerformer(new Reference(quienLoMidio));
        resultado.setValue(
                new Quantity().setValue(92).setUnit("mg/dL").setSystem(UCUM).setCode("mg/dL"));
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
