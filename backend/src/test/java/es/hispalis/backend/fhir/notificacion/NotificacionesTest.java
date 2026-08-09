package es.hispalis.backend.fhir.notificacion;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import es.hispalis.backend.infraestructura.notificacion.EntregaFirmada;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Bundle.BundleType;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations.SubscriptionStatusCodes;
import org.hl7.fhir.r5.model.Subscription;
import org.hl7.fhir.r5.model.Subscription.SubscriptionPayloadContent;
import org.hl7.fhir.r5.model.SubscriptionStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * La entrega de una {@code Subscription} de R5, de punta a punta y contra un receptor de verdad.
 *
 * <p>⚠️ <strong>Este test no se puede escribir en R4.</strong> Allí el criterio sería una cadena
 * dentro de la {@code Subscription}; aquí la suscripción solo dice a qué tópico se apunta, y el
 * tópico es un recurso de conformidad que el servidor publica. Que una suscripción a un tópico
 * inexistente se rechace —cosa que en R4 no se podía ni detectar— es uno de los casos de abajo.
 *
 * <p>El receptor es un {@code HttpServer} de la JDK, igual que el servidor de identidad de
 * {@code SeguridadSmartTest}: no es un doble del cliente HTTP, es un tercero al otro lado de un
 * puerto, y por eso puede quedarse callado o contestar un error como se quiera.
 *
 * <p><strong>Este test declara su propio {@code @SpringBootTest}</strong>, que oculta entero el del
 * padre —propiedades incluidas—, así que hay que repetir las tres suyas. Es la trampa que documenta
 * {@code backend/CLAUDE.md}; sin repetirlas, el contexto arranca con la seguridad encendida y sin
 * emisor, y no levanta.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=false",
            // Aquí SÍ se entrega: es lo que este test va a mirar.
            "hispalis.notificaciones.habilitado=true",
            "hispalis.notificaciones.intervalo=PT0.2S",
            "hispalis.notificaciones.intentos=3",
            "hispalis.notificaciones.espera-entre-intentos=PT0.05S",
            "hispalis.notificaciones.secretos.his-2026=" + NotificacionesTest.SECRETO
        })
class NotificacionesTest extends TestDeIntegracion {

    static final String SECRETO = "un-secreto-de-pruebas";

    private static final String TOPICO =
            "https://aojeda006.github.io/HispaLIS/fhir/SubscriptionTopic/resultado-validado";
    private static final Duration PACIENCIA = Duration.ofSeconds(20);

    private static Receptor receptor;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    private CircuitoDePrueba circuito;

    @BeforeAll
    static void levantarElReceptor() {
        receptor = new Receptor();
    }

    @AfterAll
    static void apagarElReceptor() {
        receptor.apagar();
    }

    @BeforeEach
    void limpiar() {
        circuito = new CircuitoDePrueba(rest, contexto);
        receptor.reiniciar();
    }

    @Test
    @DisplayName("un resultado que pasa a `final` llega al receptor, y lo que viaja NO lleva el valor dentro")
    void laEntregaLlegaYNoLlevaElValor() {
        String suscripcion = circuito.crear(suscripcionAlTopico());
        String resultado = unResultadoValidado();

        String cuerpo = esperarUnaEntrega();

        Bundle notificacion = contexto.newJsonParser().parseResource(Bundle.class, cuerpo);
        assertThat(notificacion.getType()).isEqualTo(BundleType.SUBSCRIPTIONNOTIFICATION);

        // La primera entrada TIENE que ser un SubscriptionStatus: lo exige la invariante bdl-13.
        SubscriptionStatus estado =
                (SubscriptionStatus) notificacion.getEntryFirstRep().getResource();
        assertThat(estado.getTopic()).isEqualTo(TOPICO);
        assertThat(estado.getSubscription().getReference()).isEqualTo(suscripcion);
        assertThat(estado.getNotificationEventFirstRep().getEventNumber()).isEqualTo(1);
        assertThat(estado.getNotificationEventFirstRep().getFocus().getReference())
                .isEqualTo(resultado);

        // Y la segunda lleva la identidad y una petición GET, sin recurso dentro. Esto es `id-only`.
        Bundle.BundleEntryComponent entrada = notificacion.getEntry().get(1);
        assertThat(entrada.getFullUrl()).endsWith(resultado);
        assertThat(entrada.getResource()).isNull();
        assertThat(entrada.getRequest().getMethod()).isEqualTo(Bundle.HTTPVerb.GET);

        // Lo que de verdad se está probando: por el canal no va PHI. Ni la cifra, ni la unidad, ni
        // el apellido del paciente. El invariante 6, comprobado sobre los bytes que salieron.
        assertThat(cuerpo)
                .doesNotContain("valueQuantity")
                .doesNotContain("mg/dL")
                .doesNotContain(CircuitoDePrueba.APELLIDOS)
                .doesNotContain("92");
    }

    @Test
    @DisplayName("la notificación va firmada con el secreto compartido, y el secreto no está en el recurso")
    void laEntregaVaFirmada() {
        String suscripcion = circuito.crear(suscripcionAlTopico());
        unResultadoValidado();

        String cuerpo = esperarUnaEntrega();
        Entrega recibida = receptor.entregas().get(0);

        assertThat(recibida.firma())
                .isEqualTo("his-2026=sha256:" + EntregaFirmada.firma(SECRETO, recibida.momento() + "." + cuerpo));

        // El recurso publica QUÉ clave se usa, que es un identificador. La clave en sí vive en la
        // configuración del servidor: una credencial dentro de un recurso legible por la API estaría
        // publicada a todo el que tenga permiso de lectura sobre `Subscription`.
        String publicada = rest.getForObject("/fhir/" + suscripcion, String.class);
        assertThat(publicada)
                .contains("identificador-de-clave")
                .contains("his-2026")
                .doesNotContain(SECRETO);
    }

    @Test
    @DisplayName("`$status` y `$events` contestan, y `$events` devuelve lo mismo que salió por el canal")
    void elEstadoYLosEventosContestan() {
        String suscripcion = circuito.crear(suscripcionAlTopico());
        String resultado = unResultadoValidado();
        esperarUnaEntrega();

        Bundle estado = leerBundle("/fhir/" + suscripcion + "/$status");
        SubscriptionStatus dentro =
                (SubscriptionStatus) estado.getEntryFirstRep().getResource();
        assertThat(estado.getType()).isEqualTo(BundleType.SEARCHSET);
        assertThat(dentro.getStatus()).isEqualTo(SubscriptionStatusCodes.ACTIVE);
        assertThat(dentro.getEventsSinceSubscriptionStart()).isEqualTo(1);
        assertThat(dentro.getError()).isEmpty();

        Bundle eventos = leerBundle("/fhir/" + suscripcion + "/$events");
        assertThat(eventos.getType()).isEqualTo(BundleType.SUBSCRIPTIONNOTIFICATION);
        assertThat(eventos.getEntry()).hasSize(2);
        assertThat(eventos.getEntry().get(1).getFullUrl()).endsWith(resultado);
        // `$events` tampoco es una puerta trasera por la que sacar el recurso completo.
        assertThat(eventos.getEntry().get(1).getResource()).isNull();
    }

    @Test
    @DisplayName("con el receptor devolviendo error, la suscripción acaba en `error` y `$status` dice por qué")
    void laEntregaFallidaDejaLaSuscripcionEnError() {
        receptor.queFalle();
        String suscripcion = circuito.crear(suscripcionAlTopico());
        unResultadoValidado();

        Subscription cortada = esperarA(
                () -> circuito.leer(suscripcion, Subscription.class),
                leida -> leida.getStatus() == SubscriptionStatusCodes.ERROR);

        assertThat(cortada.getStatus()).isEqualTo(SubscriptionStatusCodes.ERROR);
        assertThat(receptor.entregas()).hasSizeGreaterThanOrEqualTo(3);

        // ⚠️ El MOTIVO no está en la `Subscription`: R5 quitó `Subscription.error`, que sí existía en
        // R4. Vive en `SubscriptionStatus.error`, y por eso hay que preguntarlo con `$status`.
        SubscriptionStatus estado = (SubscriptionStatus) leerBundle("/fhir/" + suscripcion + "/$status")
                .getEntryFirstRep()
                .getResource();
        assertThat(estado.getStatus()).isEqualTo(SubscriptionStatusCodes.ERROR);
        assertThat(estado.getError()).hasSize(1);
        assertThat(estado.getErrorFirstRep().getCoding())
                .extracting(Coding::getCode)
                .containsExactly("no-response");
        assertThat(estado.getErrorFirstRep().getText()).contains("3 intentos sin entregar");

        // Y el corte: una suscripción en `error` deja de acumular trabajo. Sin esto, un receptor
        // apagado el viernes tiene el lunes miles de notificaciones esperándole.
        int entregasHastaAhora = receptor.entregas().size();
        unResultadoValidado();
        assertThat(receptor.entregas()).hasSize(entregasHastaAhora);
    }

    @Test
    @DisplayName("`full-resource` se rechaza al escribir: la historia clínica no sale por el canal")
    void elFullResourceNoSeAcepta() {
        Subscription glotona = suscripcionAlTopico();
        glotona.setContent(SubscriptionPayloadContent.FULLRESOURCE);

        ResponseEntity<String> respuesta = circuito.enviar(glotona);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody()).contains("OperationOutcome").contains("id-only");
    }

    @Test
    @DisplayName("una suscripción a un tópico que el laboratorio no publica se rechaza en vez de no recibir nunca")
    void elTopicoDesconocidoSeRechaza() {
        Subscription perdida = suscripcionAlTopico();
        perdida.setTopic("https://ejemplo.org/fhir/SubscriptionTopic/lo-que-sea");

        ResponseEntity<String> respuesta = circuito.enviar(perdida);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody()).contains("no publica el tópico");
    }

    @Test
    @DisplayName("el tópico está publicado y se puede descubrir, que es para lo que R5 lo sacó de la Subscription")
    void elTopicoSePuedeDescubrir() {
        Bundle topicos = leerBundle("/fhir/SubscriptionTopic");

        assertThat(topicos.getEntry()).isNotEmpty();
        assertThat(topicos.getEntry().stream()
                        .map(entrada -> (org.hl7.fhir.r5.model.SubscriptionTopic) entrada.getResource())
                        .map(org.hl7.fhir.r5.model.SubscriptionTopic::getUrl))
                .contains(TOPICO);
    }

    /** Una suscripción como la que pediría el HIS: al tópico del laboratorio, `id-only`, a nuestro receptor. */
    private Subscription suscripcionAlTopico() {
        Subscription suscripcion = new Subscription();
        suscripcion.setStatus(SubscriptionStatusCodes.ACTIVE);
        suscripcion.setTopic(TOPICO);
        suscripcion.setChannelType(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/subscription-channel-type")
                .setCode("rest-hook"));
        suscripcion.setEndpoint(receptor.direccion());
        suscripcion.setContentType("application/fhir+json");
        suscripcion.setContent(SubscriptionPayloadContent.IDONLY);
        suscripcion.addParameter().setName("identificador-de-clave").setValue("his-2026");
        return suscripcion;
    }

    /**
     * El circuito hasta un {@code Observation} en `final`, que es lo que dispara el tópico.
     *
     * <p><strong>La firma no es un adorno del escenario: es el disparo.</strong> Informar el
     * resultado lo publica como {@code preliminary} por mucho que el recurso que se manda diga
     * {@code final} —el laboratorio no firma por la máquina—, y hasta que un facultativo responde de
     * la cifra no hay nada que notificar. Sin el `$validar` de abajo, este test se queda esperando
     * una entrega que no tiene por qué llegar, y con razón.
     */
    private String unResultadoValidado() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));

        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));
        circuito.validar(resultado);
        return resultado;
    }

    private String esperarUnaEntrega() {
        return esperarA(() -> receptor.entregas(), entregas -> !entregas.isEmpty())
                .get(0)
                .cuerpo();
    }

    private Bundle leerBundle(String ruta) {
        ResponseEntity<String> respuesta = rest.getForEntity(ruta, String.class);
        assertThat(respuesta.getStatusCode())
                .as("%s contestó %s: %s", ruta, respuesta.getStatusCode(), respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(Bundle.class, respuesta.getBody());
    }

    /** Sondeo con plazo. El relay entrega desde otro hilo, así que aquí no hay nada a lo que unirse. */
    private static <T> T esperarA(java.util.function.Supplier<T> mirar, java.util.function.Predicate<T> yaEsta) {
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
                .as("se agotó la espera de %s", PACIENCIA)
                .isTrue();
        return ultimo;
    }

    /** Lo que llegó al otro lado: el cuerpo y las dos cabeceras de la firma. */
    private record Entrega(String cuerpo, String momento, String firma) {}

    /**
     * El HIS del hospital, simulado: un servidor HTTP de verdad que apunta lo que recibe.
     *
     * <p>Sabe hacer dos cosas y las dos hacen falta: aceptar, y negarse. Sin lo segundo no habría
     * forma de comprobar el corte, que es la mitad del criterio del ítem 44.
     */
    private static final class Receptor {

        private final HttpServer servidor;
        private final List<Entrega> recibidas = new CopyOnWriteArrayList<>();
        private final AtomicInteger respuesta = new AtomicInteger(200);

        private Receptor() {
            try {
                servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException noSePuede) {
                throw new UncheckedIOException(noSePuede);
            }
            servidor.createContext("/notificaciones", this::atender);
            servidor.start();
        }

        private void atender(HttpExchange intercambio) throws IOException {
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recibidas.add(new Entrega(
                    cuerpo,
                    intercambio.getRequestHeaders().getFirst(EntregaFirmada.CABECERA_MOMENTO),
                    intercambio.getRequestHeaders().getFirst(EntregaFirmada.CABECERA_FIRMA)));

            intercambio.sendResponseHeaders(respuesta.get(), -1);
            intercambio.close();
        }

        String direccion() {
            return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/notificaciones";
        }

        List<Entrega> entregas() {
            return List.copyOf(recibidas);
        }

        void queFalle() {
            respuesta.set(500);
        }

        void reiniciar() {
            recibidas.clear();
            respuesta.set(200);
        }

        void apagar() {
            servidor.stop(0);
        }
    }
}
