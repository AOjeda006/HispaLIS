package es.hispalis.backend.fhir.peticion;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.FacultativaDePrueba;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Annotation;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.OperationOutcome;
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

/**
 * Anulación de una línea de petición: la salida del bloqueo que dejó el invariante del informe.
 *
 * <p>Al completarse el invariante de §10 —«solo se emite con todas las líneas resueltas»— apareció
 * un estado sin salida: si la muestra de una línea se rechaza, esa línea <strong>no va a tener
 * resultado nunca</strong>, y el volante entero queda sin poder informarse hasta una nueva
 * extracción que quizá no llegue. Bloquear era el lado seguro y por eso se dejó así, pero no es lo
 * que hace un laboratorio: lo que hace es <strong>anular la línea</strong>, que en FHIR es
 * {@code ServiceRequest.status = revoked}.
 *
 * <p>Anular <strong>no es borrar</strong>. La línea se sigue publicando y se sigue leyendo; lo único
 * que cambia es que deja de estar pendiente. Borrarla dejaría el volante sin rastro de lo que se
 * pidió, que es justo lo que el peticionario necesita ver para entender por qué no lo recibe.
 *
 * <p>Y una línea anulada <strong>tampoco admite resultados después</strong>: si los admitiera, el
 * laboratorio publicaría una determinación que ya había dicho que no iba a hacer.
 */
class AnulacionDeLineaTest extends TestDeIntegracion {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";
    private static final String MOTIVO = "Muestra hemolizada y el paciente no vuelve a extracción.";
    private static final String FACULTATIVA = FacultativaDePrueba.REFERENCIA;

    // Las decenas de millón están agotadas (ver `docs/PLAN.md`, decisiones del ítem 16), así que las
    // clases nuevas se reparten DENTRO de ellas y no detrás: el NHC son exactamente ocho dígitos.
    private static final AtomicInteger SIGUIENTE = new AtomicInteger(15_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void una_linea_anulada_deja_de_bloquear_el_informe() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String volante = "P" + SIGUIENTE.incrementAndGet();

        String lineaGlucosa = crear(linea(volante, paciente, laboratorio, "GLU"));
        String lineaCreatinina = crear(linea(volante, paciente, laboratorio, "CREA"));

        String muestra = crear(muestra(paciente));
        String glucosa = validado(crear(resultado(paciente, muestra, lineaGlucosa, "GLU", 92)));

        assertThat(enviar(informe(paciente, laboratorio, glucosa)).getStatusCode())
                .as("de partida el volante está a medias, y eso ya se rechazaba")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<String> anulacion = anular(lineaCreatinina, MOTIVO);
        assertThat(anulacion.getStatusCode())
                .as("cuerpo: %s", anulacion.getBody())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> emitido = enviar(informe(paciente, laboratorio, glucosa));
        assertThat(emitido.getStatusCode())
                .as("nadie espera ya la creatinina: el volante está resuelto. Cuerpo: %s", emitido.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    /** La anulación se publica; no desaparece. El peticionario tiene que poder ver qué pasó. */
    @Test
    void la_linea_anulada_se_publica_como_revoked_y_con_su_motivo() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String linea = crear(linea("P" + SIGUIENTE.incrementAndGet(), paciente, laboratorio, "CREA"));

        anular(linea, MOTIVO);

        ServiceRequest publicada = leer(linea);
        assertThat(publicada.getStatus()).isEqualTo(Enumerations.RequestStatus.REVOKED);
        assertThat(publicada.getNoteFirstRep().getText())
                .as("sin el motivo, el peticionario tiene que llamar por teléfono para saber por qué")
                .isEqualTo(MOTIVO);
    }

    @Test
    void una_linea_anulada_no_admite_resultados_despues() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String linea = crear(linea("P" + SIGUIENTE.incrementAndGet(), paciente, laboratorio, "CREA"));
        String muestra = crear(muestra(paciente));

        anular(linea, MOTIVO);

        ResponseEntity<String> tardio = enviar(resultado(paciente, muestra, linea, "CREA", 1));

        assertThat(tardio.getStatusCode())
                .as("publicar una determinación que el laboratorio dijo que no iba a hacer")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(diagnostico(tardio)).containsIgnoringCase("anul");
    }

    /**
     * Una línea ya informada no se anula. Si se pudiera, el resultado quedaría publicado colgando de
     * una línea que dice que no se hizo — y el informe que lo llevaba, contradicho.
     */
    @Test
    void una_linea_que_ya_tiene_resultado_no_se_puede_anular() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String linea = crear(linea("P" + SIGUIENTE.incrementAndGet(), paciente, laboratorio, "GLU"));
        String muestra = crear(muestra(paciente));
        crear(resultado(paciente, muestra, linea, "GLU", 92));

        ResponseEntity<String> intento = anular(linea, MOTIVO);

        assertThat(intento.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(diagnostico(intento))
                .as("el motivo del rechazo es que ya hay resultado, no que el PUT no esté soportado")
                .containsIgnoringCase("resultado");
    }

    /**
     * El {@code PUT} sigue cerrado para todo lo que no sea anular. Es la regla de {@code ADR-0014}:
     * lo que no tiene reglas de negocio definidas se rechaza, en vez de dejar que el {@code update}
     * heredado escriba la proyección y deje el dominio atrás.
     */
    @Test
    void el_put_no_sirve_para_nada_mas_que_para_anular() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String linea = crear(linea("P" + SIGUIENTE.incrementAndGet(), paciente, laboratorio, "GLU"));

        ServiceRequest retocada = leer(linea);
        retocada.setPriority(Enumerations.RequestPriority.URGENT);

        ResponseEntity<String> intento = put(linea, retocada);

        assertThat(intento.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(diagnostico(intento)).containsIgnoringCase("anular");
    }

    /** Firma el resultado y devuelve su referencia: sin firma no entra en ningún informe. */
    private String validado(String resultado) {
        FacultativaDePrueba.darDeAlta(rest, contexto);

        Parameters facultativo = new Parameters();
        facultativo.addParameter().setName("facultativo").setValue(new Reference(FACULTATIVA));

        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + resultado + "/$validar", HttpMethod.POST, nuevaPeticion(facultativo), String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo validar %s: %s", resultado, respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        return resultado;
    }

    private ResponseEntity<String> anular(String linea, String motivo) {
        ServiceRequest revocada = leer(linea);
        revocada.setStatus(Enumerations.RequestStatus.REVOKED);
        revocada.addNote(new Annotation().setText(motivo));
        return put(linea, revocada);
    }

    private ServiceRequest leer(String referencia) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + referencia, String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(ServiceRequest.class, respuesta.getBody());
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

    private ResponseEntity<String> put(String referencia, IBaseResource recurso) {
        return rest.exchange("/fhir/" + referencia, HttpMethod.PUT, nuevaPeticion(recurso), String.class);
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
        paciente.addName(new HumanName().setFamily("Muñoz de la Torre").addGiven("Rocío"));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        return paciente;
    }

    private static ServiceRequest linea(String volante, String paciente, String solicitante, String codigo) {
        ServiceRequest peticion = new ServiceRequest();
        peticion.setStatus(Enumerations.RequestStatus.ACTIVE);
        peticion.setIntent(Enumerations.RequestIntent.ORDER);
        peticion.getRequisition().setValue(volante);
        peticion.setSubject(new Reference(paciente));
        peticion.setRequester(new Reference(solicitante));
        // R5: `code` es CodeableReference, no CodeableConcept.
        peticion.setCode(new CodeableReference(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(codigo))));
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

    private static Observation resultado(String paciente, String muestra, String linea, String codigo, double valor) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.FINAL);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(codigo)));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(muestra));
        resultado.addBasedOn(new Reference(linea));
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
