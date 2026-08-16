package es.hispalis.backend.fhir.edo;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.hispalis.backend.EsperaDelSistema;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.dominio.edo.ModalidadDeDeclaracion;
import es.hispalis.backend.dominio.edo.ReglaDeDeclaracion;
import es.hispalis.backend.dominio.resultado.ReglaRefleja;
import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import es.hispalis.backend.fhir.ResultadosCualitativos;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El notificador EDO: de la obligación apuntada a la declaración acusada por Salud Pública.
 *
 * <p>El ítem 47 dejó el laboratorio sabiendo <em>que</em> hay que declarar. Esto es lo otro: hacerlo,
 * seguirlo y poder demostrarlo. Y las tres cosas que se comprueban aquí son las tres que se rompen
 * solas si nadie las escribe como test:
 *
 * <ol>
 *   <li><strong>El envío cuelga del hecho, no de un {@code if}.</strong> Se ve en que el destinatario
 *       puede estar caído y el resultado se valida igual — si el notificador estuviera dentro de
 *       {@code ValidarResultado}, o el laboratorio dejaría de firmar resultados porque una
 *       administración no contesta, o la declaración se perdería sin dejar rastro.
 *   <li><strong>Sin acuse no hay declaración.</strong> Mandar un mensaje no es haber declarado. La
 *       diferencia entre «lo mandamos» y «lo recibieron» es exactamente lo que hay que poder contestar
 *       si alguien pregunta por qué un brote se detectó tarde.
 *   <li><strong>Sin filiación.</strong> Es el punto del proyecto donde el invariante 6 sale más barato
 *       de romper, porque el destinatario legítimamente «necesita saber». Lo que viaja aquí son
 *       códigos y referencias.
 * </ol>
 *
 * <p>El Salud Pública de estos tests es un {@code HttpServer} de la JDK, como el receptor de
 * {@code NotificacionesTest}: un tercero al otro lado de un puerto, que puede callarse, contestar sin
 * acuse o rechazar. Un doble del cliente no podría hacer ninguna de las tres.
 *
 * <p><strong>Declara su propio {@code @SpringBootTest}</strong>, que oculta entero el del padre
 * —propiedades incluidas—, así que las suyas se repiten. Es la trampa que documenta la memoria
 * técnica (§11.4).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=false",
            "hispalis.notificaciones.habilitado=false",
            // Aquí SÍ se declara: es lo que este test va a mirar.
            "hispalis.edo.habilitado=true",
            "hispalis.edo.intervalo=PT0.2S",
            "hispalis.edo.intentos=2"
        })
// La terminología de esta clase es la de abajo, no el doble compartido de `TestDeIntegracion`: dos
// beans `@Primary` del mismo tipo no conviven. Ver `TerminologiaDeLosTests`.
@org.springframework.test.context.TestPropertySource(properties = "hispalis.test.terminologia=propia")
@Import(NotificadorEdoTest.ConElCatalogoEdoYSusPlazos.class)
class NotificadorEdoTest extends TestDeIntegracion {

    /** Antígeno de Legionella: enfermedad de declaración URGENTE. */
    private static final String LEGIONELLA = "LEGIOAG";

    /** IgM de sarampión, que en este test declara con un plazo ya cumplido. Ver la terminología de abajo. */
    private static final String SARAMPION = "SARAMPIGM";

    private static final String ESTADOS =
            "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/estados-declaracion-edo";

    private static SaludPublica saludPublica;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    private CircuitoDePrueba circuito;

    @BeforeAll
    static void levantarSaludPublica() {
        saludPublica = new SaludPublica();
    }

    @AfterAll
    static void apagarSaludPublica() {
        saludPublica.apagar();
    }

    @DynamicPropertySource
    static void apuntarAlDestinatario(DynamicPropertyRegistry registro) {
        registro.add("hispalis.edo.destino", () -> saludPublica.direccion());
    }

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
        saludPublica.reiniciar();
    }

    /**
     * El circuito entero: resultado declarable validado → hecho → {@code Task} → acuse.
     *
     * <p>Lo que hace que este test valga es que <strong>nadie llama al notificador</strong>. Se valida
     * un resultado por la API y se espera; el resto pasa porque el hecho está en el {@code outbox}.
     */
    @Test
    @DisplayName("un Legionella positivo validado acaba declarado y acusado por Salud Pública")
    void elCircuitoCompleto() {
        String resultado = unResultadoCualitativo(LEGIONELLA, "POS");

        circuito.validar(resultado);

        Task declaracion = esperarLaDeclaracionEn(resultado, "ACUSADA");
        assertThat(declaracion.getFocus().getReference()).isEqualTo(resultado);
        assertThat(estadoDe(declaracion))
                .as("con acuse recibido, la declaración está hecha")
                .isEqualTo("ACUSADA");
        assertThat(declaracion.getStatus()).isEqualTo(Task.TaskStatus.COMPLETED);
        assertThat(acuseDe(declaracion))
                .as("el acuse es el número de registro que devuelve Salud Pública, y se guarda")
                .isEqualTo("SVEA-2026-000123");
        assertThat(loRecibidoSobre(resultado))
                .as("una declaración acusada no se vuelve a mandar: la obligación está cumplida")
                .hasSize(1);
    }

    /**
     * El destinatario caído, que es la mitad que decide si el diseño está bien montado.
     *
     * <p>El laboratorio no puede dejar de validar resultados porque una administración no conteste, y
     * la obligación tampoco puede evaporarse. Las dos cosas a la vez solo salen si el envío cuelga del
     * hecho.
     */
    @Test
    @DisplayName("con Salud Pública caída el resultado se valida igual y la declaración queda pendiente")
    void conElDestinatarioCaido() {
        saludPublica.queNoConteste();
        String resultado = unResultadoCualitativo(LEGIONELLA, "POS");

        circuito.validar(resultado);

        assertThat(circuito.leer(resultado, Observation.class).getStatus())
                .as("un tercero caído no puede impedir que el laboratorio responda de una cifra")
                .isEqualTo(org.hl7.fhir.r5.model.Enumerations.ObservationStatus.FINAL);

        // Primero se espera a que el intento llegue de verdad al otro lado: comprobar el estado antes
        // de eso miraría la tarea recién abierta y el test pasaría sin haber ejercitado nada.
        EsperaDelSistema.aQueAvisen(
                saludPublica.avisos(),
                () -> loRecibidoSobre(resultado),
                intentos -> !intentos.isEmpty(),
                "que el notificador llegara a intentar la declaración con el destinatario caído");

        Task declaracion = esperarLaDeclaracionEn(resultado, "PENDIENTE");
        assertThat(estadoDe(declaracion))
                .as("la obligación queda apuntada y visible, esperando a que el destinatario vuelva")
                .isEqualTo("PENDIENTE");
        assertThat(declaracion.hasOutput())
                .as("no hay acuse porque no ha contestado nadie")
                .isFalse();
    }

    /**
     * Y el invariante del ítem: <strong>un envío sin confirmación no es una declaración hecha</strong>.
     *
     * <p>Salud Pública contesta {@code 200} y no devuelve número de registro. Es el caso que más fácil
     * se cuela, porque a nivel de transporte todo ha ido bien: si el laboratorio da eso por declarado,
     * el día que haya que demostrar la declaración no habrá nada que enseñar.
     */
    @Test
    @DisplayName("un 200 sin número de registro NO cuenta como declarado")
    void sinAcuseNoHayDeclaracion() {
        saludPublica.queAcuseSinRegistro();
        String resultado = unResultadoCualitativo(LEGIONELLA, "POS");

        circuito.validar(resultado);

        Task declaracion = esperarLaDeclaracionEn(resultado, "ENVIADA");
        assertThat(estadoDe(declaracion))
                .as("mandado sí; declarado, no: sin acuse no se cierra")
                .isEqualTo("ENVIADA");
        assertThat(declaracion.getStatus()).isNotEqualTo(Task.TaskStatus.COMPLETED);
        assertThat(declaracion.hasOutput()).isFalse();
    }

    /** Si contestan que no, se anota que no. No es lo mismo que no contestar, y se distingue. */
    @Test
    @DisplayName("una declaración rechazada se anota como rechazada, no como pendiente")
    void elRechazoSeDistingueDelSilencio() {
        saludPublica.queRechace();
        String resultado = unResultadoCualitativo(LEGIONELLA, "POS");

        circuito.validar(resultado);

        Task declaracion = esperarLaDeclaracionEn(resultado, "RECHAZADA");
        assertThat(estadoDe(declaracion)).isEqualTo("RECHAZADA");
        assertThat(declaracion.getStatus()).isEqualTo(Task.TaskStatus.REJECTED);
    }

    /**
     * El plazo, que es lo que convierte la obligación en algo con fecha.
     *
     * <p>Una legionelosis es de declaración urgente: la ventana se cuenta desde que el laboratorio
     * responde del resultado, no desde que al notificador le toca el turno.
     */
    @Test
    @DisplayName("la declaración lleva su vencimiento y su prioridad")
    void elPlazoViajaEnElTask() {
        String resultado = unResultadoCualitativo(LEGIONELLA, "POS");

        circuito.validar(resultado);

        Task declaracion = esperarLaDeclaracionEn(resultado, "ACUSADA");
        assertThat(declaracion.getRestriction().getPeriod().getEnd())
                .as("un `Task` sin vencimiento no permite decir si se declaró en plazo")
                .isNotNull();
        assertThat(declaracion.getPriority())
                .as("urgente en el catálogo, urgente en el recurso")
                .isEqualTo(org.hl7.fhir.r5.model.Enumerations.RequestPriority.STAT);
    }

    /**
     * Y lo que se ha pasado de plazo se ve, sin que nadie tenga que recalcularlo.
     *
     * <p>Se busca por el {@code SearchParameter} que publica la guía sobre
     * {@code Task.restriction.period.end}: R5 no trae ninguno para ese elemento, y sin él la única
     * forma de saber qué se ha pasado sería descargar todas las declaraciones y mirarlas una a una.
     */
    @Test
    @DisplayName("una declaración fuera de plazo se encuentra buscando por su vencimiento")
    void loVencidoSeVe() {
        saludPublica.queNoConteste();
        String resultado = unResultadoCualitativo(SARAMPION, "POS");

        circuito.validar(resultado);
        Task declaracion = esperarLaDeclaracionEn(resultado, "PENDIENTE");

        Bundle vencidas = leerBundle("/fhir/Task?vencimiento=lt" + Instant.now() + "&business-status=" + "PENDIENTE");

        assertThat(vencidas.getEntry().stream()
                        .map(entrada -> entrada.getResource().getIdElement().getIdPart()))
                .as("se ha pasado el plazo legal y no se ha declarado: es justo lo que hay que poder listar")
                .contains(declaracion.getIdElement().getIdPart());
    }

    /**
     * Lo que viaja a Salud Pública: códigos y referencias.
     *
     * <p>⚠️ Una declaración EDO <strong>real</strong> lleva filiación — Salud Pública tiene que poder
     * localizar al caso para la encuesta epidemiológica—. Esta no, y no es un descuido: el
     * destinatario es simulado y el proyecto no manda datos de persona a ningún sitio. Queda escrito
     * en la guía y en la memoria técnica (§12.4).
     */
    @Test
    @DisplayName("lo que se manda no lleva nombre, ni DNI, ni NUHSA, ni NHC")
    void laDeclaracionNoLlevaFiliacion() {
        String nhc = CircuitoDePrueba.siguienteNhc();
        String resultado = unResultadoCualitativo(LEGIONELLA, "POS", nhc);

        circuito.validar(resultado);
        esperarLaDeclaracionEn(resultado, "ACUSADA");

        String enviado = loRecibidoSobre(resultado).get(0);
        assertThat(enviado)
                .doesNotContain(CircuitoDePrueba.APELLIDOS)
                .doesNotContain(CircuitoDePrueba.NOMBRE_DE_PILA)
                .doesNotContain(CircuitoDePrueba.DNI)
                .doesNotContain(CircuitoDePrueba.NUHSA)
                .doesNotContain(nhc);
        assertThat(enviado)
                .as("y sí lleva de qué se avisa: sin el código de la enfermedad la declaración no sirve")
                .contains("LEGIONELOSIS");
    }

    /** Control negativo: sin obligación no hay tarea. Sin esto, «abrir siempre un Task» pasaría por bueno. */
    @Test
    @DisplayName("un negativo no abre ninguna declaración")
    void unNegativoNoAbreNada() {
        String resultado = unResultadoCualitativo(LEGIONELLA, "NEG");

        circuito.validar(resultado);

        assertThat(declaracionesDe(resultado))
                .as("un negativo de una prueba EDO no es información para Salud Pública")
                .isEmpty();
        assertThat(loRecibidoSobre(resultado)).isEmpty();
    }

    // ─── Andamiaje ──────────────────────────────────────────────────────────

    private String unResultadoCualitativo(String prueba, String codigoDelValor) {
        return unResultadoCualitativo(prueba, codigoDelValor, CircuitoDePrueba.siguienteNhc());
    }

    private String unResultadoCualitativo(String prueba, String codigoDelValor, String nhc) {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(nhc));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));

        Observation resultado = CircuitoDePrueba.resultado(paciente, muestra, null, laboratorio);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(prueba)));
        resultado.setValue(new CodeableConcept()
                .addCoding(new Coding().setSystem(ResultadosCualitativos.SYSTEM).setCode(codigoDelValor)));
        return circuito.crear(resultado);
    }

    /** El estado frente a Salud Pública, que es el que no coincide con el {@code status} del `Task`. */
    private static String estadoDe(Task declaracion) {
        return declaracion.getBusinessStatus().getCoding().stream()
                .filter(codigo -> ESTADOS.equals(codigo.getSystem()))
                .map(Coding::getCode)
                .findFirst()
                .orElse("(sin codificar)");
    }

    /** El número de registro que devolvió Salud Pública, guardado como salida de la tarea. */
    private static String acuseDe(Task declaracion) {
        return declaracion.getOutput().stream()
                .map(salida -> salida.getValue() instanceof org.hl7.fhir.r5.model.Identifier registro
                        ? registro.getValue()
                        : salida.getValue().primitiveValue())
                .findFirst()
                .orElse("(sin acuse)");
    }

    /**
     * Lo que Salud Pública ha recibido <strong>de este resultado</strong>.
     *
     * <p>Filtrar hace falta: estos tests comparten base de datos y el notificador sigue reintentando
     * las declaraciones que otros dejaron abiertas, así que un recuento global mediría el ruido de los
     * vecinos en vez de lo que este caso hizo.
     */
    private List<String> loRecibidoSobre(String resultado) {
        return saludPublica.recibidas().stream()
                .filter(cuerpo -> cuerpo.contains(CircuitoDePrueba.identidadDe(resultado)))
                .toList();
    }

    /**
     * Espera a que exista la declaración de un resultado <strong>y</strong> haya llegado al estado que
     * el test dice. Esperar solo a que exista devolvería la tarea recién abierta, antes de intentar
     * nada, y todos los tests pasarían mirando el mismo instante.
     *
     * <p>El aviso es la escritura del propio {@code Task}: la declaración se abre y cambia de estado
     * escribiéndolo, así que no hay estado nuevo que ver sin una escritura antes.
     */
    private Task esperarLaDeclaracionEn(String resultado, String estado) {
        return espera.aQue(
                        "Task",
                        () -> declaracionesDe(resultado),
                        declaraciones -> declaraciones.size() == 1 && estado.equals(estadoDe(declaraciones.get(0))),
                        "que la declaración de " + resultado + " llegara a " + estado)
                .get(0);
    }

    private List<Task> declaracionesDe(String resultado) {
        Bundle encontradas = leerBundle("/fhir/Task?focus=" + resultado);
        return encontradas.getEntry().stream()
                .map(entrada -> (Task) entrada.getResource())
                .toList();
    }

    private Bundle leerBundle(String ruta) {
        ResponseEntity<String> respuesta = rest.getForEntity(ruta, String.class);
        assertThat(respuesta.getStatusCode())
                .as("%s contestó %s: %s", ruta, respuesta.getStatusCode(), respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
    }

    /**
     * Salud Pública, simulada: un servidor HTTP que acusa, se calla, acusa mal o rechaza.
     *
     * <p>Las cuatro respuestas hacen falta porque las cuatro pasan de verdad, y el laboratorio tiene
     * que distinguirlas. Un doble que siempre acusase dejaría sin probar justo la parte cara.
     */
    private static final class SaludPublica {

        private final HttpServer servidor;
        private final List<String> recibidas = new CopyOnWriteArrayList<>();
        private final BlockingQueue<String> avisos = new LinkedBlockingQueue<>();
        private final AtomicReference<Respuesta> respuesta = new AtomicReference<>(Respuesta.ACUSE);

        private enum Respuesta {
            ACUSE,
            SILENCIO,
            ACUSE_SIN_REGISTRO,
            RECHAZO
        }

        private SaludPublica() {
            try {
                servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException noSePuede) {
                throw new UncheckedIOException(noSePuede);
            }
            servidor.createContext("/declaraciones", this::atender);
            servidor.start();
        }

        private void atender(HttpExchange intercambio) throws IOException {
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recibidas.add(cuerpo);

            String contestacion =
                    switch (respuesta.get()) {
                        case ACUSE -> "{\"registro\":\"SVEA-2026-000123\"}";
                        case ACUSE_SIN_REGISTRO -> "{}";
                        case RECHAZO -> "{\"motivo\":\"El código de enfermedad no está en vigor.\"}";
                        case SILENCIO -> "";
                    };
            int codigo =
                    switch (respuesta.get()) {
                        case ACUSE, ACUSE_SIN_REGISTRO -> 200;
                        case RECHAZO -> 422;
                        case SILENCIO -> 503;
                    };

            byte[] salida = contestacion.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigo, salida.length);
            intercambio.getResponseBody().write(salida);
            intercambio.close();
            avisos.add("un intento de declaración");
        }

        String direccion() {
            return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/declaraciones";
        }

        List<String> recibidas() {
            return List.copyOf(recibidas);
        }

        BlockingQueue<String> avisos() {
            return avisos;
        }

        void queNoConteste() {
            respuesta.set(Respuesta.SILENCIO);
        }

        void queAcuseSinRegistro() {
            respuesta.set(Respuesta.ACUSE_SIN_REGISTRO);
        }

        void queRechace() {
            respuesta.set(Respuesta.RECHAZO);
        }

        void reiniciar() {
            recibidas.clear();
            avisos.clear();
            respuesta.set(Respuesta.ACUSE);
        }

        void apagar() {
            servidor.stop(0);
        }
    }

    /**
     * El catálogo EDO de este test: una enfermedad urgente y otra cuyo plazo ya ha vencido.
     *
     * <p>La segunda es un atajo declarado: el plazo real del sarampión son horas, y esperarlas en un
     * test no es una opción. Lo que se está probando no es cuántas horas dice el catálogo —eso lo
     * comprueba {@code TerminologiaDelServidorTest} contra un servidor de verdad—, sino que un
     * vencimiento pasado se pueda encontrar.
     */
    @TestConfiguration
    static class ConElCatalogoEdoYSusPlazos {

        private static final Map<String, String> NOMBRES =
                Map.of("POS", "Positivo", "NEG", "Negativo", "IND", "Indeterminado");

        @Bean
        @Primary
        Terminologia terminologiaConPlazos() {
            return new Terminologia() {

                @Override
                public CodeableConcept pruebaDelCatalogo(String codigoLocal) {
                    return new CodeableConcept()
                            .addCoding(new Coding()
                                    .setSystem(CatalogoDePruebas.SYSTEM)
                                    .setCode(codigoLocal));
                }

                @Override
                public CodeableConcept valorCualitativo(String codigoLocal) {
                    return new CodeableConcept()
                            .addCoding(new Coding()
                                    .setSystem(ResultadosCualitativos.SYSTEM)
                                    .setCode(codigoLocal)
                                    .setDisplay(NOMBRES.get(codigoLocal)))
                            .setText(NOMBRES.get(codigoLocal));
                }

                @Override
                public void exigirQueLaPruebaExiste(String codigoLocal) {
                    // Sin autoridad a la que preguntar, rechazar sería inventarse la respuesta.
                }

                @Override
                public Optional<UmbralCritico> umbralDe(String codigoDePrueba) {
                    return Optional.empty();
                }

                @Override
                public Optional<ReglaRefleja> reflejaDe(String codigoDePrueba) {
                    return Optional.empty();
                }

                @Override
                public Optional<ReglaDeDeclaracion> declaracionDe(String codigoDePrueba) {
                    return switch (codigoDePrueba) {
                        case LEGIONELLA ->
                            Optional.of(new ReglaDeDeclaracion(
                                    LEGIONELLA,
                                    "LEGIONELOSIS",
                                    "Legionelosis",
                                    "POS",
                                    ModalidadDeDeclaracion.URGENTE,
                                    Duration.ofHours(24)));
                        // Un milisegundo de plazo: el vencimiento queda en el pasado en cuanto se
                        // abre la declaración. El plazo real del sarampión son horas y esperarlas
                        // en un test no es una opción; lo que se prueba aquí no es cuántas dice el
                        // catálogo, sino que un vencimiento pasado se pueda encontrar.
                        case SARAMPION ->
                            Optional.of(new ReglaDeDeclaracion(
                                    SARAMPION,
                                    "SARAMPION",
                                    "Sarampión",
                                    "POS",
                                    ModalidadDeDeclaracion.URGENTE,
                                    Duration.ofMillis(1)));
                        default -> Optional.empty();
                    };
                }
            };
        }
    }
}
