package es.hispalis.backend.fhir.resultado;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import es.hispalis.backend.fhir.seguridad.ServidorDeIdentidadDePruebas;
import java.math.BigDecimal;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Practitioner;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Specimen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * La doble validación de un crítico, <strong>con la seguridad encendida</strong>.
 *
 * <h2>Por qué esta clase existe aparte de {@code DobleValidacionTest}</h2>
 *
 * <p>Porque las dos condiciones que hacen falta para ver el fallo vivían en clases distintas y
 * ninguna las tenía a la vez. {@code DobleValidacionTest} tiene el umbral crítico y apaga la
 * seguridad; {@code SeguridadSmartTest} la enciende y corre con el catálogo que no declara ningún
 * umbral, así que allí todo se valida a la primera firma. Con una firma, la procedencia que escribe
 * {@code $validar} es un <strong>alta</strong>; con la segunda, {@code ValidarResultado} reescribe
 * las procedencias del resultado y la primera pasa a ser una <strong>modificación</strong>. Una
 * regla que solo autorizaba {@code create} sobre {@code Provenance} deja pasar la primera firma y
 * rechaza la segunda con un {@code 403} que no dice qué recurso lo provocó.
 *
 * <p>El resultado neto era que <strong>un crítico no se podía terminar de validar contra la pila con
 * seguridad</strong>: se quedaba firmado a medias, en {@code preliminary}, y por tanto fuera de
 * cualquier informe. Apareció recorriendo el circuito del ítem 51 contra el {@code compose}, no en
 * los tests. La corrección y el porqué de que no abra ninguna puerta están en {@code adr-0033}.
 *
 * <p>La terminología es la misma de {@code DobleValidacionTest} —de ahí que la clase viva en este
 * paquete— para que el umbral del potasio sea uno solo y no dos copias que puedan discrepar.
 */
// Repetir el `@SpringBootTest` entero es obligado: una clase que declara el suyo oculta el del padre
// con sus propiedades, así que la seguridad se enciende aquí y el emisor se apunta abajo.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=true",
            "hispalis.seguridad.audiencias=" + ServidorDeIdentidadDePruebas.AUDIENCIA,
            "hispalis.seguridad.tiempo-de-espera=PT2S",
            "hispalis.test.terminologia=propia"
        })
@Import(DobleValidacionTest.ConLosUmbralesDelCatalogo.class)
class DobleValidacionConSeguridadTest extends TestDeIntegracion {

    private static final ServidorDeIdentidadDePruebas IDENTIDAD = ServidorDeIdentidadDePruebas.elDeSiempre();

    private static final String SNOMED = "http://snomed.info/sct";
    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SANGRE_VENOSA = "122555007";

    /** El tramo de NHC de esta clase: el 32 quedaba libre al lado del 31 de su clase hermana. */
    private static final java.util.concurrent.atomic.AtomicInteger SIGUIENTE =
            new java.util.concurrent.atomic.AtomicInteger(32_000_000);

    private static final String DE_GUARDIA = "Practitioner/analisis-clinicos-guardia";
    private static final String DE_MANANA = "Practitioner/analisis-clinicos";

    /** Lo que lleva el testigo de quien firma: leer todo, dar de alta el escenario y validar. */
    private static final String SCOPES = "openid fhirUser launch user/*.rs "
            + "user/Patient.c user/Specimen.c user/Observation.cu user/Practitioner.u";

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void apuntarAlServidorDeIdentidad(DynamicPropertyRegistry registro) {
        registro.add("hispalis.seguridad.emisor", IDENTIDAD::emisor);
    }

    /**
     * El caso que el {@code compose} destapó: la segunda firma de un crítico, con testigo.
     *
     * <p>No comprueba nada nuevo del dominio —eso ya lo prueba {@code DobleValidacionTest}—: lo que
     * comprueba es que la autorización deja terminar lo que ya autorizó a empezar. Que el resultado
     * acabe en {@code final} es el efecto; que la segunda procedencia se pueda escribir es la causa.
     */
    @Test
    @DisplayName("la segunda firma de un crítico también pasa con la seguridad puesta")
    void la_segunda_firma_de_un_critico_pasa_con_seguridad() {
        String deManana = testigoDe("dra.alvarez", DE_MANANA);
        String deGuardia = testigoDe("dr.munoz", DE_GUARDIA);
        darDeAlta(DE_MANANA, "Álvarez Peña", deManana);
        darDeAlta(DE_GUARDIA, "Muñoz de la Torre", deGuardia);
        String resultado = potasioDe("6.9", deManana);

        ResponseEntity<String> primera = validar(resultado, DE_MANANA, deManana);
        ResponseEntity<String> segunda = validar(resultado, DE_GUARDIA, deGuardia);

        assertThat(primera.getStatusCode())
                .as("la primera firma sí pasaba: la procedencia que escribe es un alta. Cuerpo: %s", primera.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(segunda.getStatusCode())
                .as("la segunda reescribe la procedencia de la primera, y eso es un UPDATE: %s", segunda.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(leerResultado(resultado, deGuardia).getStatus())
                .as("un crítico que no puede recibir la segunda firma se queda fuera de todo informe")
                .isEqualTo(Enumerations.ObservationStatus.FINAL);
        assertThat(procedenciasDe(resultado, deGuardia))
                .as("y cada firma sigue dejando la suya, que es de lo que da fe la doble validación")
                .hasSize(2);
    }

    // ─── Andamiaje ──────────────────────────────────────────────────────────

    private String testigoDe(String sujeto, String facultativa) {
        return IDENTIDAD.testigo(sujeto, SCOPES, null, facultativa);
    }

    private void darDeAlta(String referencia, String apellidos, String testigo) {
        Practitioner quien = new Practitioner();
        quien.setId(referencia.substring(referencia.indexOf('/') + 1));
        quien.addName(new HumanName().setFamily(apellidos).addGiven("Marta"));

        ResponseEntity<String> alta =
                rest.exchange("/fhir/" + referencia, HttpMethod.PUT, peticionCon(quien, testigo), String.class);
        assertThat(alta.getStatusCode())
                .as("sin el directorio sembrado no hay quien firme: %s", alta.getBody())
                .isIn(HttpStatus.OK, HttpStatus.CREATED);
    }

    private String potasioDe(String valor, String testigo) {
        String paciente = crear(paciente(), testigo);
        String muestra = crear(muestra(paciente), testigo);
        return crear(resultado(paciente, muestra, new BigDecimal(valor)), testigo);
    }

    private ResponseEntity<String> validar(String resultado, String facultativa, String testigo) {
        Parameters parametros = new Parameters();
        parametros.addParameter().setName("facultativo").setValue(new Reference(facultativa));
        return rest.exchange(
                "/fhir/" + resultado + "/$validar", HttpMethod.POST, peticionCon(parametros, testigo), String.class);
    }

    private Observation leerResultado(String referencia, String testigo) {
        ResponseEntity<String> respuesta =
                rest.exchange("/fhir/" + referencia, HttpMethod.GET, conTestigo(testigo), String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Observation.class, respuesta.getBody());
    }

    private java.util.List<Provenance> procedenciasDe(String resultado, String testigo) {
        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/Provenance?target=" + resultado, HttpMethod.GET, conTestigo(testigo), String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);

        return contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody()).getEntry().stream()
                .map(entrada -> (Provenance) entrada.getResource())
                .toList();
    }

    private String crear(IBaseResource recurso, String testigo) {
        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + recurso.fhirType(), HttpMethod.POST, peticionCon(recurso, testigo), String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo preparar el escenario con %s: %s", recurso.fhirType(), respuesta.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String location = respuesta.getHeaders().getFirst(HttpHeaders.LOCATION);
        return location.substring(0, location.indexOf("/_history")).substring(location.indexOf("/fhir/") + 6);
    }

    private HttpEntity<String> peticionCon(IBaseResource recurso, String testigo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(testigo);
        cabeceras.setContentType(MediaType.valueOf("application/fhir+json"));
        return new HttpEntity<>(contexto.newJsonParser().encodeResourceToString(recurso), cabeceras);
    }

    private HttpEntity<Void> conTestigo(String testigo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(testigo);
        return new HttpEntity<>(cabeceras);
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

    private static Observation resultado(String paciente, String muestra, BigDecimal valor) {
        Observation resultado = new Observation();
        resultado.setStatus(Enumerations.ObservationStatus.PRELIMINARY);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("K")));
        resultado.setSubject(new Reference(paciente));
        resultado.setSpecimen(new Reference(muestra));
        resultado.setValue(
                new Quantity().setValue(valor).setUnit("mmol/L").setSystem(UCUM).setCode("mmol/L"));
        return resultado;
    }
}
