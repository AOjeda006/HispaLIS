package es.hispalis.backend.fhir.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.hl7.fhir.r5.model.AuditEvent;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * La traza de acceso: quién, qué, cuándo y desde dónde — y ni una palabra de más.
 *
 * <p>{@code AuditEvent} es el recurso que más fácil se llena de datos que no debería tener, y por una
 * razón que suena razonable: para investigar un incidente parece que cuanto más se guarde, mejor. Es
 * al revés. La traza es un registro que se conserva años, que lee gente de sistemas y no de la
 * consulta, y que se exporta a un SIEM: <strong>lo que se meta ahí sale del ámbito clínico para
 * siempre</strong>.
 *
 * <p>De ahí las tres reglas que este test convierte en asertos:
 *
 * <ol>
 *   <li><strong>Referencias, nunca volcados.</strong> {@code entity.what} apunta al recurso; el
 *       recurso no viaja dentro.
 *   <li><strong>Nunca el criterio de búsqueda.</strong> {@code AuditEvent.entity.query} es el elemento
 *       que el estándar reserva para eso, y es exactamente donde acabaría el número de historia de
 *       {@code GET /fhir/Patient?identifier=…} (adr-0016). Se deja vacío a propósito.
 *   <li><strong>Y aun así, completa.</strong> Una traza sin agente, sin instante o sin recurso no sirve
 *       para lo único que tiene que servir: reconstruir quién vio qué.
 * </ol>
 *
 * <p><strong>La traza se escribe después de contestar</strong>, y es una decisión, no un descuido: un
 * laboratorio que dejara de entregar un resultado porque la tabla de auditoría no admite escrituras
 * sería un fallo peor que el que se intenta evitar. Es el mismo criterio que con Salud Pública en el
 * ítem 48. Por eso los asertos esperan.
 */
class TrazaDeAccesoTest extends TestDeIntegracion {

    private static final Duration PACIENCIA = Duration.ofSeconds(10);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    private CircuitoDePrueba circuito;

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
    }

    // ─── Que esté ───────────────────────────────────────────────────────────

    /** Un alta deja constancia de que se creó, de qué se creó y de cuándo. */
    @Test
    @DisplayName("un alta deja su traza, con acción `C` y el recurso creado como entidad")
    void unaEscrituraDejaTraza() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));

        AuditEvent traza = esperarLaTrazaDe(paciente, AuditEvent.AuditEventAction.C);

        assertThat(traza.getRecorded())
                .as("una traza sin instante no sitúa nada")
                .isNotNull();
        assertThat(traza.getCode().getCodingFirstRep().getCode())
                .as("qué interacción REST fue, en el vocabulario del estándar")
                .isEqualTo("create");
        assertThat(traza.getEntity().stream().map(entidad -> entidad.getWhat().getReference()))
                .contains(paciente);
    }

    /** Y una lectura también. Es la mitad que se olvida, y la que más veces ocurre. */
    @Test
    @DisplayName("una lectura deja su traza, con acción `R`")
    void unaLecturaDejaTraza() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));

        circuito.leer(paciente, Patient.class);

        AuditEvent traza = esperarLaTrazaDe(paciente, AuditEvent.AuditEventAction.R);
        assertThat(traza.getCode().getCodingFirstRep().getCode()).isEqualTo("read");
    }

    /** Quién, y desde dónde. Sin las dos cosas, la traza no responde a la pregunta que se le hace. */
    @Test
    @DisplayName("la traza dice quién llamó, si era él quien lo pedía y desde qué dirección")
    void laTrazaDiceQuienYDesdeDonde() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));

        AuditEvent traza = esperarLaTrazaDe(paciente, AuditEvent.AuditEventAction.C);

        AuditEvent.AuditEventAgentComponent quien = traza.getAgentFirstRep();
        assertThat(quien.getWho().hasReference() || quien.getWho().hasIdentifier())
                .as("un agente sin identificar no distingue a nadie de nadie")
                .isTrue();
        assertThat(quien.getRequestor())
                .as("quien pidió el acto, frente a los sistemas que solo participaron")
                .isTrue();
        assertThat(quien.getNetwork())
                .as("⚠️ R5: `agent.network[x]`, no el `agent.network` con `address`/`type` de R4")
                .isNotNull();
        assertThat(traza.getSource().getObserver().hasReference())
                .as("quién levanta acta: este servidor")
                .isTrue();
    }

    /** El acto que sale mal también deja rastro. Es, de hecho, el que más falta hace. */
    @Test
    @DisplayName("una petición que falla deja traza con desenlace distinto de correcto")
    void loQueFallaTambienDejaTraza() {
        Instant desde = Instant.now();

        ResponseEntity<String> fallida = rest.getForEntity("/fhir/Patient/no-existe-este-paciente", String.class);
        assertThat(fallida.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        List<AuditEvent> trazas = esperarA(
                () -> buscar("/fhir/AuditEvent?date=gt" + desde + "&outcome:not=0"),
                encontradas -> !encontradas.isEmpty());
        assertThat(trazas.stream().map(traza -> traza.getOutcome().getCode().getCode()))
                .as("un intento fallido es justo lo que se busca al investigar un incidente")
                .doesNotContain("0");
    }

    // ─── Y que no lleve lo que no debe ──────────────────────────────────────

    /**
     * El aserto del ítem: se recorre el circuito entero y <strong>ninguna</strong> traza lleva
     * filiación.
     */
    @Test
    @DisplayName("recorrido el circuito completo, ninguna traza lleva nombre, DNI, NUHSA ni NHC")
    void laTrazaNoLlevaPhi() {
        Instant desde = Instant.now();
        String nhc = CircuitoDePrueba.siguienteNhc();

        String paciente = circuito.crear(CircuitoDePrueba.paciente(nhc));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));
        circuito.validar(resultado);
        circuito.leer(resultado, Observation.class);
        rest.getForEntity("/fhir/Observation?subject=" + paciente, String.class);

        String todaLaTraza = esperarA(
                () -> comoTexto(buscar("/fhir/AuditEvent?date=gt" + desde + "&_count=200")),
                texto -> texto.contains(CircuitoDePrueba.identidadDe(resultado)));

        assertThat(todaLaTraza)
                .doesNotContain(CircuitoDePrueba.APELLIDOS)
                .doesNotContain(CircuitoDePrueba.NOMBRE_DE_PILA)
                .doesNotContain(CircuitoDePrueba.DNI)
                .doesNotContain(CircuitoDePrueba.NUHSA)
                .doesNotContain(nhc);
        assertThat(todaLaTraza)
                .as("y sí lleva a qué recurso se accedió: una traza sin eso no reconstruye nada")
                .contains(CircuitoDePrueba.identidadDe(paciente));
    }

    /**
     * El elemento concreto donde acabaría el número de historia si nadie lo impidiera.
     *
     * <p>{@code AuditEvent.entity.query} guarda la consulta en base64 — de forma que ni siquiera se ve
     * al leer el recurso, que es lo que lo hace peligroso. Una búsqueda por NHC dejaría ahí el NHC.
     */
    @Test
    @DisplayName("una búsqueda por NHC no guarda el criterio: `entity.query` va siempre vacío")
    void laTrazaNoGuardaElCriterioDeBusqueda() {
        Instant desde = Instant.now();
        String nhc = CircuitoDePrueba.siguienteNhc();
        circuito.crear(CircuitoDePrueba.paciente(nhc));

        rest.getForEntity(
                "/fhir/Patient?identifier=https://aojeda006.github.io/HispaLIS/sid/nhc|" + nhc, String.class);

        List<AuditEvent> trazas = esperarA(
                () -> buscar("/fhir/AuditEvent?date=gt" + desde + "&_count=200"),
                encontradas -> encontradas.stream()
                        .anyMatch(traza -> traza.getAction() == AuditEvent.AuditEventAction.E));

        assertThat(trazas.stream().flatMap(traza -> traza.getEntity().stream()))
                .as("el criterio es donde va el NHC, y por eso este elemento no se rellena nunca")
                .noneMatch(AuditEvent.AuditEventEntityComponent::hasQuery);
        assertThat(comoTexto(trazas)).doesNotContain(nhc);
    }

    /** Una traza que el cliente puede escribir no es una traza: es lo que él quiera contar. */
    @Test
    @DisplayName("un cliente no puede escribir una traza de acceso")
    void laTrazaNoSeEscribeDesdeFuera() {
        AuditEvent inventada = new AuditEvent();
        inventada.setRecorded(new java.util.Date());
        // El agente va por identificador y no por referencia a propósito: con una referencia colgando,
        // HAPI la rechazaría por integridad referencial y el test pasaría sin haber comprobado nada de
        // lo que dice comprobar.
        inventada
                .addAgent()
                .getWho()
                .getIdentifier()
                .setSystem("https://ejemplo.invalido/quien")
                .setValue("quien-sea");

        ResponseEntity<String> respuesta = circuito.enviar(inventada);

        assertThat(respuesta.getStatusCode())
                .as("si el cliente escribe el registro, el registro deja de dar fe de nada: %s", respuesta.getBody())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─── Andamiaje ──────────────────────────────────────────────────────────

    private AuditEvent esperarLaTrazaDe(String recurso, AuditEvent.AuditEventAction accion) {
        return esperarA(
                        () -> buscar("/fhir/AuditEvent?entity=" + recurso + "&action=" + accion.toCode()),
                        encontradas -> !encontradas.isEmpty())
                .get(0);
    }

    private List<AuditEvent> buscar(String ruta) {
        ResponseEntity<String> respuesta = rest.getForEntity(ruta, String.class);
        assertThat(respuesta.getStatusCode())
                .as("%s contestó %s: %s", ruta, respuesta.getStatusCode(), respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        Bundle encontradas = contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
        return encontradas.getEntry().stream()
                .map(entrada -> (AuditEvent) entrada.getResource())
                .toList();
    }

    private String comoTexto(List<AuditEvent> trazas) {
        return trazas.stream()
                .map(traza -> contexto.newJsonParser().encodeResourceToString(traza))
                .reduce("", String::concat);
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
