package es.hispalis.backend.fhir.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.seguridad.ServidorDeIdentidadDePruebas;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.Bundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * La traza de quien llega con testigo — <strong>incluido el que no está en el directorio</strong>.
 *
 * <p>Este test existe por un fallo medido, no por completitud. Con {@code agent.who} escrito como
 * referencia literal al {@code fhirUser} del testigo, la traza de una facultativa que no figura en el
 * {@code Practitioner} del laboratorio <strong>no se podía guardar</strong>: HAPI comprueba la
 * integridad referencial al escribir y rechazaba el {@code AuditEvent} entero con
 * {@code HAPI-1094: Resource Practitioner/dra-alvarez not found}. El acceso ocurría, se contestaba, y
 * del registro de auditoría desaparecía.
 *
 * <p>Y de todos los accesos posibles, ése es el que más falta hace registrar: <strong>alguien con un
 * testigo válido que no está en nuestro directorio</strong>. Es la misma trampa de {@code adr-0030} por
 * otro camino, y por eso la comprobación va aquí y no en el javadoc: los siete casos de
 * {@code TrazaDeAccesoTest} corren sin seguridad, así que ninguno llega a tener {@code fhirUser}.
 *
 * <p>El caso elegido acumula las dos mitades a propósito: un {@code fhirUser} que no existe pidiendo un
 * paciente que tampoco. Si la traza sobrevive a eso, sobrevive a todo.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=true",
            "hispalis.seguridad.audiencias=" + ServidorDeIdentidadDePruebas.AUDIENCIA,
            "hispalis.seguridad.tiempo-de-espera=PT2S"
        })
class TrazaConTestigoTest extends TestDeIntegracion {

    private static final Duration PACIENCIA = Duration.ofSeconds(10);

    private static final ServidorDeIdentidadDePruebas IDENTIDAD = ServidorDeIdentidadDePruebas.elDeSiempre();

    /** Nadie con este número de colegiado está dado de alta, y es justo el sentido del caso. */
    private static final String LA_QUE_NO_ESTA = "Practitioner/dra-que-no-figura-en-el-directorio";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    @DynamicPropertySource
    static void apuntarAlServidorDeIdentidad(DynamicPropertyRegistry registro) {
        registro.add("hispalis.seguridad.emisor", IDENTIDAD::emisor);
    }

    @Test
    @DisplayName("un `fhirUser` que no está en el directorio deja traza igual, por identificador")
    void laTrazaDeQuienNoEstaEnElDirectorioTambienSeGuarda() {
        Instant desde = Instant.now();

        ResponseEntity<String> fallida = pedir(
                "/fhir/Patient/tampoco-existe-este-paciente",
                IDENTIDAD.testigo("dra.fantasma", "user/Patient.rs", null, LA_QUE_NO_ESTA));
        assertThat(fallida.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        List<AuditEvent> trazas =
                esperarA(() -> buscar("/fhir/AuditEvent?date=gt" + desde), encontradas -> encontradas.stream()
                        .anyMatch(TrazaConTestigoTest::laFirmaLaQueNoEsta));

        AuditEvent suya = trazas.stream()
                .filter(TrazaConTestigoTest::laFirmaLaQueNoEsta)
                .findFirst()
                .orElseThrow();

        assertThat(suya.getAgentFirstRep().getWho().hasReference())
                .as("una referencia literal a quien no está en el directorio impediría guardar la traza")
                .isFalse();
        assertThat(suya.getAgentFirstRep().getWho().getType())
                .as("qué clase de usuario era, aunque no se pueda resolver")
                .isEqualTo("Practitioner");
        assertThat(suya.getOutcome().getCode().getCode())
                .as("y que el acceso se le negó: es la mitad del registro que se investiga")
                .isNotEqualTo("0");
    }

    private static boolean laFirmaLaQueNoEsta(AuditEvent traza) {
        return LA_QUE_NO_ESTA.equals(
                traza.getAgentFirstRep().getWho().getIdentifier().getValue());
    }

    private ResponseEntity<String> pedir(String ruta, String testigo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(testigo);
        return rest.exchange(ruta, HttpMethod.GET, new HttpEntity<>(cabeceras), String.class);
    }

    /** Las trazas se leen con un testigo de sistema: es un recurso más y la puerta también le aplica. */
    private List<AuditEvent> buscar(String ruta) {
        ResponseEntity<String> respuesta =
                pedir(ruta, IDENTIDAD.testigo("vigilancia", "system/AuditEvent.rs", null, null));
        assertThat(respuesta.getStatusCode())
                .as("%s contestó %s: %s", ruta, respuesta.getStatusCode(), respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        Bundle encontradas = contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
        return encontradas.getEntry().stream()
                .map(entrada -> (AuditEvent) entrada.getResource())
                .toList();
    }

    private static <T> T esperarA(Supplier<T> mirar, Predicate<T> yaEsta) {
        Instant limite = Instant.now().plus(PACIENCIA);
        T ultimo = mirar.get();
        while (!yaEsta.test(ultimo) && Instant.now().isBefore(limite)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrumpido) {
                Thread.currentThread().interrupt();
                break;
            }
            ultimo = mirar.get();
        }
        assertThat(yaEsta.test(ultimo))
                .as("se agotó la espera de %s sin que la traza apareciera", PACIENCIA)
                .isTrue();
        return ultimo;
    }
}
