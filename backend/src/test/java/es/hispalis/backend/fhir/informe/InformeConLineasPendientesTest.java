package es.hispalis.backend.fhir.informe;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.OperationOutcome;
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
 * El invariante del informe de §10, completo: <strong>solo se emite con todas las líneas de la
 * petición resueltas</strong>.
 *
 * <p>Hasta aquí el laboratorio solo exigía que el informe no estuviera vacío y que no mezclara
 * pacientes. Faltaba el caso que de verdad hace daño en un laboratorio: el volante trae cinco
 * determinaciones, dos están hechas, y el informe sale con esas dos. No parece un error —trae
 * resultados, todos correctos, del paciente correcto—, pero el peticionario lo lee como la respuesta
 * a lo que pidió y <strong>deja de esperar las otras tres</strong>. Un informe vacío se detecta solo;
 * este no.
 *
 * <p>Se comprueba por la API, y el compañero {@code InformeTest} lo comprueba en el núcleo sin
 * levantar nada: los dos prueban cosas distintas, y solo el segundo demuestra dónde vive la regla.
 */
class InformeConLineasPendientesTest extends TestDeIntegracion {

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";

    // Cada clase de test arranca en su propia decena de millón para no chocar con las demás: el NHC
    // es único en el laboratorio y los tests comparten base de datos. Las nueve decenas están
    // ocupadas, así que este va a la mitad de la última: el NHC son EXACTAMENTE ocho dígitos, y
    // seguir la serie con 100_000_000 lo rechaza el propio dominio.
    private static final AtomicInteger SIGUIENTE = new AtomicInteger(95_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void un_informe_no_se_emite_con_una_linea_del_volante_pendiente() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String volante = "P" + SIGUIENTE.incrementAndGet();

        // Un volante con dos determinaciones. Comparten número: es la misma petición.
        String lineaGlucosa = crear(linea(volante, paciente, laboratorio, "GLU"));
        crear(linea(volante, paciente, laboratorio, "CREA"));

        String muestra = crear(muestra(paciente));
        String glucosa = crear(resultado(paciente, muestra, lineaGlucosa, "GLU", 92));

        ResponseEntity<String> intento = enviar(informe(paciente, laboratorio, glucosa));

        assertThat(intento.getStatusCode())
                .as("con la creatinina aún en el analizador, este informe dice que ya no hay nada que esperar")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        OperationOutcome fallo = contexto.newJsonParser().parseResource(OperationOutcome.class, intento.getBody());
        assertThat(fallo.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(fallo.getIssueFirstRep().getDiagnostics())
                .as("hay que decir qué falta: si no, el laboratorio tiene que buscarlo a mano")
                .containsIgnoringCase("CREA");
    }

    /**
     * Control positivo. Sin él, un invariante implementado como «rechaza siempre» pasaría el test de
     * arriba con matrícula de honor y dejaría al laboratorio sin poder emitir un solo informe.
     */
    @Test
    void con_las_dos_lineas_resueltas_el_informe_sale() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String volante = "P" + SIGUIENTE.incrementAndGet();

        String lineaGlucosa = crear(linea(volante, paciente, laboratorio, "GLU"));
        String lineaCreatinina = crear(linea(volante, paciente, laboratorio, "CREA"));

        String muestra = crear(muestra(paciente));
        String glucosa = crear(resultado(paciente, muestra, lineaGlucosa, "GLU", 92));
        String creatinina = crear(resultado(paciente, muestra, lineaCreatinina, "CREA", 1));

        ResponseEntity<String> emitido = enviar(informe(paciente, laboratorio, glucosa, creatinina));

        assertThat(emitido.getStatusCode())
                .as("cuerpo del error: %s", emitido.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    /**
     * El volante entero está resuelto aunque el informe solo cite una de las dos determinaciones.
     * Lo que bloquea la emisión es que quede trabajo por hacer, no que el informe deje algo fuera:
     * un resultado ya entregado en un informe anterior no tiene por qué repetirse en este.
     */
    @Test
    void una_linea_resuelta_en_otro_informe_no_bloquea_este() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());
        String volante = "P" + SIGUIENTE.incrementAndGet();

        String lineaGlucosa = crear(linea(volante, paciente, laboratorio, "GLU"));
        String lineaCreatinina = crear(linea(volante, paciente, laboratorio, "CREA"));

        String muestra = crear(muestra(paciente));
        String glucosa = crear(resultado(paciente, muestra, lineaGlucosa, "GLU", 92));
        crear(resultado(paciente, muestra, lineaCreatinina, "CREA", 1));

        ResponseEntity<String> emitido = enviar(informe(paciente, laboratorio, glucosa));

        assertThat(emitido.getStatusCode())
                .as("cuerpo del error: %s", emitido.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    /**
     * Una repetición de control no la pidió nadie por volante, así que no hay líneas que resolver y
     * el informe sale. Sin este caso, el invariante habría dejado sin informar todo lo que el
     * laboratorio añade por su cuenta.
     */
    @Test
    void un_resultado_sin_volante_se_informa_igual() {
        String laboratorio = crear(laboratorio());
        String paciente = crear(paciente());

        String muestra = crear(muestra(paciente));
        String suelto = crear(resultado(paciente, muestra, null, "GLU", 92));

        ResponseEntity<String> emitido = enviar(informe(paciente, laboratorio, suelto));

        assertThat(emitido.getStatusCode())
                .as("cuerpo del error: %s", emitido.getBody())
                .isEqualTo(HttpStatus.CREATED);
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
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        String cuerpo = contexto.newJsonParser().encodeResourceToString(recurso);

        return rest.exchange(
                "/fhir/" + recurso.fhirType(), HttpMethod.POST, new HttpEntity<>(cuerpo, cabeceras), String.class);
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
        paciente.addName(new HumanName().setFamily("Álvarez de la Peña").addGiven("Nuria"));
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
        if (linea != null) {
            resultado.addBasedOn(new Reference(linea));
        }
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
