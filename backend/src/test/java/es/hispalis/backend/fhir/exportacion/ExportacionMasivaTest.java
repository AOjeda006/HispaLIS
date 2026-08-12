package es.hispalis.backend.fhir.exportacion;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.hispalis.backend.EsperaDelSistema;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.aplicacion.exportacion.BarrerExportaciones;
import es.hispalis.backend.aplicacion.exportacion.CerrarExportacion;
import es.hispalis.backend.dominio.edo.ModalidadDeDeclaracion;
import es.hispalis.backend.dominio.edo.ReglaDeDeclaracion;
import es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros;
import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Group;
import org.hl7.fhir.r5.model.Observation;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bulk Data: la cohorte de vigilancia, exportada — y lo que pasa con el fichero después.
 *
 * <p>Es la operación con la que más fácil se filtra una población entera, y por eso lo que se prueba
 * aquí no es solo que funcione. Son cuatro cosas, y las cuatro son de las que no avisan al romperse:
 *
 * <ol>
 *   <li><strong>Asíncrona de verdad.</strong> {@code 202} con {@code Content-Location}, sondeo y
 *       manifiesto. Una exportación que contestara {@code 200} con los datos dentro sería una búsqueda
 *       muy grande con otro nombre, y se caería sola el día que la cohorte tenga mil personas.
 *   <li><strong>Sin filiación.</strong> Lo que sale es la cohorte seudonimizada: sexo, año de
 *       nacimiento y municipio. Es la misma postura que la declaración del ítem 48, y aquí importa más
 *       porque no es una persona: son todas.
 *   <li><strong>Nada de PHI en la URL ni en el nombre del fichero.</strong> Una URL viaja al log del
 *       proxy, al historial y a la analítica (adr-0016). El fichero se pide por un billete opaco.
 *   <li><strong>Caduca y se borra, y se comprueba mirando el disco.</strong> Un NDJSON con la cohorte
 *       de una enfermedad en una carpeta es exactamente lo que este proyecto lleva dos hitos evitando.
 * </ol>
 *
 * <p>La cohorte no se inventa para el test: sale de las declaraciones EDO del ítem 48, que es lo que
 * le da a esta exportación un motivo legal (§4.4). Por eso el notificador va encendido y hay un Salud
 * Pública simulado al otro lado — sin declaraciones no hay cohorte que exportar.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=false",
            "hispalis.notificaciones.habilitado=false",
            "hispalis.edo.habilitado=true",
            "hispalis.edo.intervalo=PT0.2S",
            "hispalis.edo.intentos=2",
            // Caducidad de segundos y barrido continuo: lo que se prueba es que el barrendero borra,
            // no cuántos minutos dice la configuración de producción.
            "hispalis.exportacion.habilitada=true",
            "hispalis.exportacion.caducidad=PT3S",
            "hispalis.exportacion.barrido=PT0.3S"
        })
@org.springframework.test.context.TestPropertySource(properties = "hispalis.test.terminologia=propia")
@Import(ExportacionMasivaTest.ConElCatalogoEdo.class)
class ExportacionMasivaTest extends TestDeIntegracion {

    private static final String LEGIONELLA = "LEGIOAG";

    /** La cohorte que abre el laboratorio al declarar una legionelosis. */
    private static final String COHORTE = "Group/cohorte-legionelosis";

    /**
     * Los dos avisos que esta clase no puede recibir por una escritura FHIR.
     *
     * <p>La exportación deja NDJSON en el disco y cierra su trabajo en el esquema del dominio, y el
     * barrendero lo retira del mismo sitio: ni una cosa ni la otra pasan por las DAO de HAPI, así que
     * {@link EsperaDelSistema#aQue} no las ve. El aviso lo ponen ellos mismos, envueltos abajo en
     * {@link ConElCatalogoEdo}.
     */
    static final BlockingQueue<String> EXPORTACIONES_TERMINADAS = new LinkedBlockingQueue<>();

    static final BlockingQueue<String> BARRIDOS = new LinkedBlockingQueue<>();

    private static SaludPublicaQueAcusa saludPublica;
    private static Path directorio;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    private final ObjectMapper json = new ObjectMapper();

    private CircuitoDePrueba circuito;

    @BeforeAll
    static void prepararElEntorno() throws IOException {
        saludPublica = new SaludPublicaQueAcusa();
        directorio = Files.createTempDirectory("hispalis-exportaciones");
    }

    @AfterAll
    static void recogerElEntorno() {
        saludPublica.apagar();
    }

    @DynamicPropertySource
    static void apuntarAlDestinatarioYAlDisco(DynamicPropertyRegistry registro) {
        registro.add("hispalis.edo.destino", () -> saludPublica.direccion());
        registro.add("hispalis.exportacion.directorio", () -> directorio.toString());
    }

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
    }

    // ─── La cohorte ─────────────────────────────────────────────────────────

    /**
     * El {@code Group} no lo trae un cliente: lo abre el laboratorio al declarar.
     *
     * <p>Es lo que hace que la exportación tenga detrás un motivo legal y no la lista de pacientes que
     * a alguien le apeteciera pedir.
     */
    @Test
    @DisplayName("declarar una legionelosis mete al paciente en la cohorte de vigilancia")
    void laCohorteSaleDeLasDeclaraciones() {
        String resultado = unLegionellaPositivo();

        circuito.validar(resultado);

        // Se espera a ESTE paciente y no a que la cohorte tenga alguien: los tests comparten base de
        // datos, así que «no vacía» la cumple el caso que dejó el test anterior y esto pasaría sin
        // haber comprobado nada.
        esperarALaCohorteCon(resultado);
        Group cohorte = leerLaCohorte().orElseThrow();
        assertThat(cohorte.getType()).isEqualTo(Group.GroupType.PERSON);
        assertThat(cohorte.getMembership())
                .as("R5 sustituyó `actual` por `membership`: los miembros están enumerados uno a uno")
                .isEqualTo(Group.GroupMembershipBasis.ENUMERATED);
        assertThat(cohorte.getMember().stream()
                        .map(miembro -> miembro.getEntity().getReference()))
                .as("el sujeto del resultado declarado es, por definición, un caso de la cohorte")
                .contains(pacienteDe(resultado));
    }

    /** Y no se escribe desde fuera. Una cohorte que el cliente compone es la lista que él quiera. */
    @Test
    @DisplayName("un cliente no puede crear ni modificar una cohorte")
    void laCohorteNoSeEscribeDesdeFuera() {
        Group inventada = new Group();
        inventada.setType(Group.GroupType.PERSON);
        inventada.setMembership(Group.GroupMembershipBasis.ENUMERATED);

        ResponseEntity<String> respuesta = circuito.enviar(inventada);

        assertThat(respuesta.getStatusCode())
                .as("si el cliente compone la cohorte, exporta a quien quiera: %s", respuesta.getBody())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─── El circuito de la exportación ──────────────────────────────────────

    /** {@code 202}, sondeo, manifiesto y NDJSON. El camino entero del estándar. */
    @Test
    @DisplayName("$export contesta 202, se sondea y acaba en un manifiesto con NDJSON por tipo")
    void elCircuitoDeLaExportacion() throws Exception {
        String resultado = unLegionellaPositivo();
        circuito.validar(resultado);
        esperarALaCohorteCon(resultado);

        ResponseEntity<String> lanzamiento = lanzar();

        assertThat(lanzamiento.getStatusCode())
                .as("una exportación devuelve el trabajo, no los datos: %s", lanzamiento.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        String sondeo = lanzamiento.getHeaders().getFirst(HttpHeaders.CONTENT_LOCATION);
        assertThat(sondeo)
                .as("sin `Content-Location` el cliente no tiene a dónde volver")
                .isNotBlank();

        JsonNode manifiesto = esperarAlManifiesto(sondeo);

        assertThat(manifiesto.get("transactionTime").asText())
                .as("es el corte temporal, y con él se pide la carga incremental siguiente")
                .isNotBlank();
        assertThat(manifiesto.get("requiresAccessToken").asBoolean())
                .as("los ficheros los sirve este mismo servidor y siguen exigiendo testigo")
                .isTrue();
        assertThat(manifiesto.get("request").asText()).contains("$export");

        List<String> tipos = textos(manifiesto.get("output"), "type");
        assertThat(tipos)
                .as("un fichero por tipo de recurso, que es como se ingiere sin adivinar")
                .contains("Patient", "Observation");

        String ndjson = descargar(urlDe(manifiesto, "Observation"));
        assertThat(ndjson.lines())
                .as("NDJSON: un recurso por línea, para poder ingerirlo sin cargarlo entero")
                .allMatch(linea -> linea.startsWith("{\"resourceType\":\"Observation\""));
        assertThat(ndjson.lines().count()).isEqualTo(cuentaDe(manifiesto, "Observation"));
    }

    /** El array {@code error} existe aunque esté vacío: darlo por bueno sin mirarlo es el anti-patrón. */
    @Test
    @DisplayName("el manifiesto trae los arrays `deleted` y `error`, aunque vengan vacíos")
    void elManifiestoDeclaraLoQueNoTrae() throws Exception {
        String resultado = unLegionellaPositivo();
        circuito.validar(resultado);
        esperarALaCohorteCon(resultado);

        JsonNode manifiesto = esperarAlManifiesto(lanzarYSondear());

        assertThat(manifiesto.has("error"))
                .as("un éxito parcial es conforme, y solo se ve leyendo este array")
                .isTrue();
        assertThat(manifiesto.has("deleted")).isTrue();
    }

    // ─── Lo que sale, y lo que no ───────────────────────────────────────────

    /**
     * La cohorte va seudonimizada: sexo, año de nacimiento y municipio.
     *
     * <p>Divergencia consciente de un servidor Bulk conforme, que sacaría el compartimento tal cual.
     * Aquí no: lo epidemiológico es el resultado y el dónde, no el nombre.
     */
    @Test
    @DisplayName("el NDJSON no lleva nombre, ni DNI, ni NUHSA, ni NHC")
    void loQueSaleNoLlevaFiliacion() throws Exception {
        String nhc = CircuitoDePrueba.siguienteNhc();
        String resultado = unLegionellaPositivo(nhc);
        circuito.validar(resultado);
        esperarALaCohorteCon(resultado);

        JsonNode manifiesto = esperarAlManifiesto(lanzarYSondear());
        String todo = todoLoDescargado(manifiesto);

        assertThat(todo)
                .doesNotContain(CircuitoDePrueba.APELLIDOS)
                .doesNotContain(CircuitoDePrueba.NOMBRE_DE_PILA)
                .doesNotContain(CircuitoDePrueba.DNI)
                .doesNotContain(CircuitoDePrueba.NUHSA)
                .doesNotContain(nhc);
        assertThat(todo)
                .as("y sí lleva el dato epidemiológico: sin sexo ni el código de la prueba no sirve de nada")
                .contains("\"gender\"")
                .contains(LEGIONELLA);
    }

    /** Ni la URL de descarga ni el nombre del fichero pueden decir de quién son los datos. */
    @Test
    @DisplayName("la URL de descarga es opaca y el nombre del fichero no dice de quién es")
    void nadaDePhiEnLaUrlDeDescarga() throws Exception {
        String nhc = CircuitoDePrueba.siguienteNhc();
        String resultado = unLegionellaPositivo(nhc);
        circuito.validar(resultado);
        esperarALaCohorteCon(resultado);

        JsonNode manifiesto = esperarAlManifiesto(lanzarYSondear());

        for (JsonNode fichero : manifiesto.get("output")) {
            assertThat(fichero.get("url").asText())
                    .doesNotContain(nhc)
                    .doesNotContain(CircuitoDePrueba.APELLIDOS)
                    .doesNotContain(CircuitoDePrueba.NUHSA)
                    .doesNotContain(CircuitoDePrueba.identidadDe(pacienteDe(resultado)));
        }
        try (Stream<Path> enDisco = Files.walk(directorio)) {
            assertThat(enDisco.map(ruta -> ruta.getFileName().toString()))
                    .as("el nombre de un fichero acaba en un log de copia de seguridad")
                    .noneMatch(nombre -> nombre.contains(nhc));
        }
    }

    // ─── Y lo que pasa con el fichero después ───────────────────────────────

    /** Lo que exige el ítem: caduca y se borra, y se comprueba en el disco. */
    @Test
    @DisplayName("pasada la caducidad el fichero se borra del disco y el sondeo deja de encontrarlo")
    void elFicheroCaducaYSeBorra() throws Exception {
        String resultado = unLegionellaPositivo();
        circuito.validar(resultado);
        esperarALaCohorteCon(resultado);

        String sondeo = lanzarYSondear();
        JsonNode manifiesto = esperarAlManifiesto(sondeo);
        String descarga = urlDe(manifiesto, "Observation");

        assertThat(rest.exchange(descarga, HttpMethod.GET, vacia(), String.class)
                        .getStatusCode())
                .as("recién terminada la exportación, el fichero está")
                .isEqualTo(HttpStatus.OK);

        // El plazo lo cuenta el sistema, no el test: se vuelve a mirar tras CADA pasada del
        // barrendero, que es el único que puede retirar esto. Si el fichero sigue ahí después de una
        // pasada, es que aún está en plazo; y si el barrendero se hubiera muerto, no habría pasadas.
        EsperaDelSistema.aQueAvisen(
                BARRIDOS,
                () -> rest.exchange(sondeo, HttpMethod.GET, vacia(), String.class)
                        .getStatusCode(),
                estado -> estado == HttpStatus.NOT_FOUND,
                "que el barrendero retirara la exportación caducada");

        assertThat(rest.exchange(descarga, HttpMethod.GET, vacia(), String.class)
                        .getStatusCode())
                .as("un enlace caducado no sirve datos: se vuelve a pedir el manifiesto, y ya no hay")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ficherosDe(sondeo))
                .as("y el volcado no se queda en el disco esperando a que alguien se acuerde")
                .isEmpty();
    }

    /** Y el cliente ordenado no espera a la caducidad: dice que ya está y se borra en el momento. */
    @Test
    @DisplayName("un DELETE sobre la URL de sondeo borra el fichero ya y deja el sondeo en 404")
    void cancelarBorraElFicheroEnElActo() throws Exception {
        String resultado = unLegionellaPositivo();
        circuito.validar(resultado);
        esperarALaCohorteCon(resultado);

        String sondeo = lanzarYSondear();
        esperarAlManifiesto(sondeo);

        ResponseEntity<String> cancelacion = rest.exchange(sondeo, HttpMethod.DELETE, vacia(), String.class);

        assertThat(cancelacion.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(rest.exchange(sondeo, HttpMethod.GET, vacia(), String.class).getStatusCode())
                .as("después de un DELETE el trabajo no existe, y la norma dice 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ficherosDe(sondeo)).isEmpty();
    }

    // ─── Los bordes ─────────────────────────────────────────────────────────

    /** Un parámetro que no se soporta da error, y no se ignora. Es lo contrario de la búsqueda normal. */
    @Test
    @DisplayName("un parámetro no soportado se rechaza con OperationOutcome, no se ignora")
    void unParametroQueNoSeSoportaSeRechaza() {
        ResponseEntity<String> respuesta = rest.exchange(
                "/fhir/" + COHORTE + "/$export?_since=2026-01-01T00:00:00Z", HttpMethod.POST, vacia(), String.class);

        assertThat(respuesta.getStatusCode())
                .as("ignorarlo devolvería más datos de los que el cliente pidió, y sin decírselo")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(respuesta.getBody()).contains("OperationOutcome").contains("_since");
    }

    /** Y no se exporta cualquier `Group`: solo las cohortes de vigilancia que abre el laboratorio. */
    @Test
    @DisplayName("exportar un grupo que no existe es 404, no una exportación vacía")
    void soloSeExportaLaCohorteQueExiste() {
        ResponseEntity<String> respuesta =
                rest.exchange("/fhir/Group/cohorte-inventada/$export", HttpMethod.POST, vacia(), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── Andamiaje ──────────────────────────────────────────────────────────

    private String unLegionellaPositivo() {
        return unLegionellaPositivo(CircuitoDePrueba.siguienteNhc());
    }

    private String unLegionellaPositivo(String nhc) {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(nhc));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));

        Observation resultado = CircuitoDePrueba.resultado(paciente, muestra, null, laboratorio);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(LEGIONELLA)));
        resultado.setValue(new CodeableConcept()
                .addCoding(new Coding().setSystem(ResultadosCualitativos.SYSTEM).setCode("POS")));
        return circuito.crear(resultado);
    }

    private String pacienteDe(String resultado) {
        return circuito.leer(resultado, Observation.class).getSubject().getReference();
    }

    /**
     * La cohorte la llena el notificador EDO desde otro hilo, y el aviso es <strong>el propio
     * {@code Group} confirmado</strong>: cuando llega, el miembro está.
     */
    private void esperarALaCohorteCon(String resultado) {
        String paciente = pacienteDe(resultado);
        espera.aQue(
                "Group",
                this::leerLaCohorte,
                cohorte -> cohorte.isPresent()
                        && cohorte.get().getMember().stream()
                                .anyMatch(miembro ->
                                        paciente.equals(miembro.getEntity().getReference())),
                "que la declaración metiera a " + paciente + " en la cohorte de vigilancia");
    }

    private Optional<Group> leerLaCohorte() {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/" + COHORTE, String.class);
        return respuesta.getStatusCode() == HttpStatus.OK
                ? Optional.of(contexto.newJsonParser().parseResource(Group.class, respuesta.getBody()))
                : Optional.empty();
    }

    private ResponseEntity<String> lanzar() {
        return rest.exchange("/fhir/" + COHORTE + "/$export", HttpMethod.POST, conPreferAsincrono(), String.class);
    }

    private String lanzarYSondear() {
        ResponseEntity<String> lanzamiento = lanzar();
        assertThat(lanzamiento.getStatusCode())
                .as("no se pudo lanzar la exportación: %s", lanzamiento.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        return lanzamiento.getHeaders().getFirst(HttpHeaders.CONTENT_LOCATION);
    }

    /**
     * La exportación no escribe ningún recurso —deja NDJSON en el disco y cierra su trabajo en el
     * esquema del dominio—, así que el aviso lo pone el <strong>propio ejecutor</strong>, envuelto
     * abajo: cuando la tarea de fondo termina, hay manifiesto o hay error, y las dos cosas se ven en
     * el sondeo.
     */
    private JsonNode esperarAlManifiesto(String sondeo) throws IOException {
        ResponseEntity<String> terminado = EsperaDelSistema.aQueAvisen(
                EXPORTACIONES_TERMINADAS,
                () -> rest.exchange(sondeo, HttpMethod.GET, vacia(), String.class),
                respuesta -> respuesta.getStatusCode() != HttpStatus.ACCEPTED,
                "que el hilo de exportación terminara el trabajo de " + sondeo);

        assertThat(terminado.getStatusCode())
                .as("el sondeo tenía que acabar en el manifiesto: %s", terminado.getBody())
                .isEqualTo(HttpStatus.OK);
        return json.readTree(terminado.getBody());
    }

    private String descargar(String url) {
        ResponseEntity<String> respuesta = rest.exchange(url, HttpMethod.GET, vacia(), String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo descargar %s: %s", url, respuesta.getBody())
                .isEqualTo(HttpStatus.OK);
        return respuesta.getBody();
    }

    private String todoLoDescargado(JsonNode manifiesto) {
        StringBuilder todo = new StringBuilder();
        for (JsonNode fichero : manifiesto.get("output")) {
            todo.append(descargar(fichero.get("url").asText()));
        }
        return todo.toString();
    }

    /**
     * Lo que queda en el disco <strong>de esta exportación</strong>.
     *
     * <p>Acotado al trabajo y no a la carpeta entera: los tests de esta clase comparten directorio y
     * corren seguidos, así que un recuento global mediría los ficheros del vecino —que todavía están en
     * plazo— y este test fallaría por algo que no es suyo.
     */
    private List<Path> ficherosDe(String sondeo) throws IOException {
        Path carpeta = directorio.resolve(sondeo.substring(sondeo.indexOf("_jobId=") + "_jobId=".length()));
        if (!Files.exists(carpeta)) {
            return List.of();
        }
        try (Stream<Path> rutas = Files.walk(carpeta)) {
            return rutas.filter(Files::isRegularFile).toList();
        }
    }

    private static String urlDe(JsonNode manifiesto, String tipo) {
        for (JsonNode fichero : manifiesto.get("output")) {
            if (tipo.equals(fichero.get("type").asText())) {
                return fichero.get("url").asText();
            }
        }
        throw new AssertionError("El manifiesto no trae ningún fichero de " + tipo);
    }

    private static long cuentaDe(JsonNode manifiesto, String tipo) {
        for (JsonNode fichero : manifiesto.get("output")) {
            if (tipo.equals(fichero.get("type").asText())) {
                return fichero.get("count").asLong();
            }
        }
        throw new AssertionError("El manifiesto no trae ningún fichero de " + tipo);
    }

    private static List<String> textos(JsonNode array, String campo) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(nodo -> nodo.get(campo).asText())
                .toList();
    }

    private static HttpEntity<Void> vacia() {
        return new HttpEntity<>(new HttpHeaders());
    }

    private static HttpEntity<Void> conPreferAsincrono() {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setAccept(List.of(MediaType.valueOf("application/fhir+json")));
        cabeceras.add("Prefer", "respond-async");
        return new HttpEntity<>(cabeceras);
    }

    /** Salud Pública, que aquí solo tiene que acusar: lo que se prueba es la cohorte, no la declaración. */
    private static final class SaludPublicaQueAcusa {

        private final HttpServer servidor;

        private SaludPublicaQueAcusa() {
            try {
                servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException noSePuede) {
                throw new UncheckedIOException(noSePuede);
            }
            servidor.createContext("/declaraciones", this::atender);
            servidor.start();
        }

        private void atender(HttpExchange intercambio) throws IOException {
            intercambio.getRequestBody().readAllBytes();
            byte[] salida = "{\"registro\":\"SVEA-2026-000900\"}".getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, salida.length);
            intercambio.getResponseBody().write(salida);
            intercambio.close();
        }

        String direccion() {
            return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/declaraciones";
        }

        void apagar() {
            servidor.stop(0);
        }
    }

    /** El catálogo EDO mínimo —una enfermedad, para que haya cohorte— y los dos avisos de arriba. */
    @TestConfiguration
    static class ConElCatalogoEdo {

        private static final Map<String, String> NOMBRES = Map.of("POS", "Positivo", "NEG", "Negativo");

        /**
         * El mismo hilo único de producción, pero avisando al terminar.
         *
         * <p>Envolver el ejecutor y no llamar al caso de uso a mano es lo que mantiene la prueba
         * honesta: la exportación sigue ocurriendo <strong>de verdad en otro hilo</strong>, con su
         * {@code 202} y su sondeo, que es la mitad de lo que este test comprueba. Lo único que cambia
         * es que el test se entera de cuándo acabó en vez de adivinarlo.
         *
         * <p>El aviso va en un {@code finally}: una exportación que revienta también termina, y el
         * sondeo lo cuenta con un error que el test tiene que poder ver.
         */
        @Bean
        @Primary
        Executor hiloDeExportacionQueAvisa() {
            Executor unSoloHilo = Executors.newSingleThreadExecutor(tarea -> {
                Thread hilo = new Thread(tarea, "exportacion-de-prueba");
                hilo.setDaemon(true);
                return hilo;
            });
            return tarea -> unSoloHilo.execute(() -> {
                try {
                    tarea.run();
                } finally {
                    EXPORTACIONES_TERMINADAS.add("una exportación ha terminado");
                }
            });
        }

        /** Y el barrendero, igual: cada pasada avisa, la borre o no. */
        @Bean
        @Primary
        BarrerExportaciones barrerAvisandoDeCadaPasada(
                RepositorioDeExportaciones trabajos, AlmacenDeFicheros almacen, CerrarExportacion cerrar) {
            return new BarrerExportaciones(trabajos, almacen, cerrar) {
                @Override
                public void ejecutar() {
                    try {
                        super.ejecutar();
                    } finally {
                        BARRIDOS.add("una pasada del barrendero");
                    }
                }
            };
        }

        @Bean
        @Primary
        Terminologia terminologiaConLegionelosis() {
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
                    return LEGIONELLA.equals(codigoDePrueba)
                            ? Optional.of(new ReglaDeDeclaracion(
                                    LEGIONELLA,
                                    "LEGIONELOSIS",
                                    "Legionelosis",
                                    "POS",
                                    ModalidadDeDeclaracion.URGENTE,
                                    Duration.ofHours(24)))
                            : Optional.empty();
                }
            };
        }
    }
}
