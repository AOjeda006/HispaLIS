package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Recorrer el circuito desde un test: petición → muestra → resultado → firma → informe.
 *
 * <p>Existe porque varios tests necesitan <em>llegar</em> al estado que quieren comprobar y ninguno
 * de ellos va de cómo se llega. Los recursos que construye son los mínimos que el dominio acepta:
 * quien necesite uno más rico —el test de aceptación, que vuelca lo que revisa el validador oficial—
 * se lo arma él, y hace bien.
 *
 * <p>Los apellidos llevan {@code Ñ} y tilde <strong>siempre</strong>. No es color local: son casos de
 * prueba obligatorios del proyecto, y un escenario montado con «Garcia Lopez» dejaría pasar
 * exactamente los fallos de codificación que hay que cazar.
 */
public final class CircuitoDePrueba {

    public static final String SNOMED = "http://snomed.info/sct";
    public static final String UCUM = "http://unitsofmeasure.org";
    public static final String SANGRE_VENOSA = "122555007";
    public static final String NICA = "https://aojeda006.github.io/HispaLIS/sid/nica";

    /** Filiación que no puede aparecer ni en un hecho, ni en un log, ni en el bus. */
    public static final String APELLIDOS = "Muñoz Peñalver";

    public static final String OTROS_APELLIDOS = "Álvarez de la Peña";
    public static final String NOMBRE_DE_PILA = "Begoña";
    public static final String DNI = "12345678Z";
    public static final String NUHSA = "AN0123456789";

    /**
     * Numerador de NHC, números de acceso y volantes. Es estático y compartido porque el PostgreSQL
     * embebido también lo es: dos clases de test que empiecen a contar por su cuenta acabarían
     * chocando en el NHC, que el laboratorio exige único.
     *
     * <p>Cada clase de test que aún tiene su propio numerador arranca en un millón distinto. El 45 es
     * el hueco que quedaba libre: el 40 lo ocupa {@code ConcurrenciaOptimistaTest}, y compartirlo
     * hacía fallar todo lo que corriera después con «ya hay un paciente con ese NHC».
     */
    private static final AtomicInteger SIGUIENTE = new AtomicInteger(45_000_000);

    private final TestRestTemplate rest;
    private final FhirContext contexto;
    private final String testigo;

    public CircuitoDePrueba(TestRestTemplate rest, FhirContext contexto) {
        this(rest, contexto, null);
    }

    /**
     * El mismo circuito, pero firmando cada petición con un testigo.
     *
     * <p>Casi todos los tests corren con la seguridad apagada y no lo necesitan. Lo necesita el que
     * la enciende, y ese es el único que puede comprobar lo que solo falla con ella puesta.
     *
     * @param testigo el testigo de acceso, o {@code null} para no mandar ninguno
     */
    public CircuitoDePrueba(TestRestTemplate rest, FhirContext contexto, String testigo) {
        this.rest = rest;
        this.contexto = contexto;
        this.testigo = testigo;
    }

    public static String siguienteNhc() {
        return String.valueOf(SIGUIENTE.incrementAndGet());
    }

    /** Crea el recurso y devuelve su referencia, {@code Tipo/id}. Falla si no responde 201. */
    public String crear(IBaseResource recurso) {
        ResponseEntity<String> respuesta = enviar(recurso);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo preparar el escenario con %s: %s", recurso.fhirType(), respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String location = respuesta.getHeaders().getFirst(HttpHeaders.LOCATION);
        return location.substring(0, location.indexOf("/_history")).substring(location.indexOf("/fhir/") + 6);
    }

    public ResponseEntity<String> enviar(IBaseResource recurso) {
        return rest.exchange("/fhir/" + recurso.fhirType(), HttpMethod.POST, peticionCon(recurso), String.class);
    }

    /** Firma el resultado. Sin este paso el informe no sale, y con razón. */
    public void validar(String resultado) {
        FacultativaDePrueba.darDeAlta(rest, contexto);

        Parameters facultativo = new Parameters();
        facultativo.addParameter().setName("facultativo").setValue(new Reference(FacultativaDePrueba.REFERENCIA));

        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + resultado + "/$validar", HttpMethod.POST, peticionCon(facultativo), String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo validar %s: %s", resultado, respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    public <T extends IBaseResource> T leer(String referencia, Class<T> tipo) {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + referencia, String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo leer %s: %s", referencia, respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(tipo, respuesta.getBody());
    }

    public HttpEntity<String> peticionCon(IBaseResource recurso) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        if (testigo != null) {
            cabeceras.setBearerAuth(testigo);
        }
        return new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(recurso), cabeceras);
    }

    /** El identificador desnudo de una referencia {@code Tipo/id}. */
    public static String identidadDe(String referencia) {
        return referencia.substring(referencia.indexOf('/') + 1);
    }

    public static Organization laboratorio() {
        Organization laboratorio = new Organization();
        laboratorio.addIdentifier().setSystem(NICA).setValue("NICA" + SIGUIENTE.incrementAndGet());
        laboratorio.setName("Laboratorio HispaLIS");
        return laboratorio;
    }

    public static Patient paciente(String nhc) {
        return paciente(nhc, APELLIDOS);
    }

    public static Patient paciente(String nhc, String apellidos) {
        Patient paciente = new Patient();
        paciente.addIdentifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc);
        paciente.addIdentifier().setSystem(SistemasDeIdentificador.DNI_NIE).setValue(DNI);
        paciente.addIdentifier()
                .setSystem(SistemasDeIdentificador.CIP_AUTONOMICO)
                .setValue(NUHSA);
        paciente.addName(new HumanName().setFamily(apellidos).addGiven(NOMBRE_DE_PILA));
        paciente.setGender(Enumerations.AdministrativeGender.FEMALE);
        return paciente;
    }

    public static ServiceRequest linea(String paciente, String solicitante) {
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

    public static Specimen muestra(String paciente) {
        Specimen especimen = new Specimen();
        especimen.getAccessionIdentifier().setValue("A" + SIGUIENTE.incrementAndGet());
        especimen.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        especimen.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(SANGRE_VENOSA)));
        especimen.setSubject(new Reference(paciente));
        return especimen;
    }

    public static Observation resultado(String paciente, String muestra, String linea, String quienLoMidio) {
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

    public static DiagnosticReport informe(String paciente, String emisor, String... resultados) {
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
