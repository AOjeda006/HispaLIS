package es.hispalis.integracion.infraestructura.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.IdentidadDePrueba;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * El motor se identifica ante el laboratorio como cliente {@code system/} (D5, SMART Backend Services).
 *
 * <p>Lo que hace fuerte a este test es que <strong>el servidor de identidad comprueba la aserción de
 * verdad</strong>: se baja el JWKS de la URL que publica el motor y verifica con él la firma RS384. Si
 * el motor firmara con otra clave, publicara mal su JWKS o usara otro algoritmo, no habría testigo —
 * exactamente lo que pasaría con Keycloak. Un doble que devolviera un testigo sin mirar dejaría sin
 * probar justo la parte que cuesta acertar.
 *
 * <p>Y el recorrido se cierra por el otro extremo: se manda un {@code ADT^A01} por MLLP y se comprueba
 * que la escritura llegó al laboratorio con el {@code Authorization: Bearer} que emitió el servidor de
 * identidad. Entre las dos puntas está todo lo que hay que creerse.
 */
class BackendServicesTest extends TestDelMotor {

    /** Su tramo de NHC, como cada clase: la base de datos es una sola para toda la ejecución. */
    private static final AtomicInteger SIGUIENTE = new AtomicInteger(76_000_000);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int puerto;

    @Autowired
    private TestigoDeSistema testigos;

    /**
     * Cada test empieza sin testigo guardado.
     *
     * <p>Sin esto, el primero que escribe se lleva el único canje y los demás miran una lista de
     * aserciones vacía: el contexto de Spring —y con él la caché del testigo— es uno para toda la
     * clase. Tirar el testigo a mano es lo que hace que cada test valga por sí solo.
     */
    @BeforeEach
    void empezarSinTestigo() {
        LABORATORIO.olvidarTodo();
        IDENTIDAD.olvidarTodo();
        testigos.olvidarlo();
    }

    /**
     * El JWKS publica la clave pública y <strong>solo</strong> la pública.
     *
     * <p>El fallo que esto vigila no da ningún error: un JWKS con {@code d} dentro sirve igual de bien
     * para verificar firmas, y nadie se entera hasta que alguien lo mira. Y lo que se publica ahí es
     * público de verdad: lo lee cualquiera que alcance al motor.
     */
    @Test
    void el_motor_publica_su_clave_publica_y_nada_mas() throws Exception {
        JsonNode jwks = json.readTree(pedir("/motor/jwks.json"));
        JsonNode clave = jwks.get("keys").get(0);

        assertThat(jwks.get("keys")).hasSize(1);
        assertThat(clave.get("kty").asText()).isEqualTo("RSA");
        assertThat(clave.get("alg").asText())
                .as("RS384 es el algoritmo que la norma exige soportar para las aserciones de cliente")
                .isEqualTo("RS384");
        assertThat(clave.get("use").asText()).isEqualTo("sig");
        assertThat(clave.get("kid").asText()).isNotBlank();
        assertThat(clave.has("n")).isTrue();
        assertThat(clave.has("e")).isTrue();
        assertThat(List.of("d", "p", "q", "dp", "dq", "qi"))
                .as("ni un trozo de la clave privada puede salir de aquí")
                .noneMatch(clave::has);
    }

    /**
     * El recorrido entero: un mensaje entra por MLLP y la escritura sale firmada.
     *
     * <p>El testigo que llega al laboratorio es el mismo que emitió el servidor de identidad, y para
     * emitirlo tuvo que verificar la firma contra el JWKS del motor. Con eso queda probada la cadena
     * completa sin creerse ningún eslabón.
     */
    @Test
    void la_escritura_en_el_laboratorio_va_firmada_con_el_testigo_de_sistema() {
        enviarUnAlta();

        assertThat(LABORATORIO.escrituras())
                .as("sin escritura no hay nada que comprobar: el canal tiene que haber llegado al final")
                .isPositive();
        assertThat(LABORATORIO.ultimaAutorizacion())
                .as("el motor escribe como cliente system/, no de forma anónima (D5)")
                .startsWith("Bearer testigo-");
        assertThat(IDENTIDAD.canjes()).isEqualTo(1);
    }

    /** Los {@code scope} que pide son los suyos, y {@code $reconciliar} no está entre ellos. */
    @Test
    void pide_solo_los_scopes_de_sistema_que_necesita() {
        enviarUnAlta();

        String pedidos = IDENTIDAD.scopesPedidos().get(0);
        assertThat(pedidos).contains("system/Patient.crus", "system/ServiceRequest.cs", "system/Observation.crs");
        assertThat(pedidos)
                .as("`$reconciliar` borra recursos de cualquier tipo: no se pide «por si acaso»")
                .doesNotContain("system/*.cruds");
    }

    /**
     * La aserción cumple lo que la norma exige de ella.
     *
     * <p>Los cuatro puntos se incumplen con facilidad y ninguno da error visible al principio: un
     * {@code aud} equivocado funciona contra un servidor permisivo, un {@code jti} repetido pasa
     * desapercibido hasta que alguien reproduce una petición, y una aserción de una hora es una
     * credencial de larga vida viajando por la red.
     */
    @Test
    void la_asercion_de_cliente_cumple_lo_que_pide_la_norma() {
        enviarUnAlta();

        JWTClaimsSet asercion = IDENTIDAD.aserciones().get(0);
        assertThat(asercion.getIssuer()).isEqualTo("hispalis-motor");
        assertThat(asercion.getSubject())
                .as("iss y sub iguales: una aserción no puede hablar en nombre de otro cliente")
                .isEqualTo(asercion.getIssuer());
        assertThat(asercion.getAudience()).containsExactly(IDENTIDAD.puntoDeTestigo());
        assertThat(asercion.getJWTID()).isNotBlank();

        Duration vida = Duration.ofMillis(
                asercion.getExpirationTime().getTime() - asercion.getIssueTime().getTime());
        assertThat(vida)
                .as("la norma le da cinco minutos como mucho: es una credencial viajando por la red")
                .isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    /**
     * El testigo se guarda: dos mensajes, un solo canje.
     *
     * <p>Pedir uno por escritura funcionaría y sería un error caro — un viaje extra al servidor de
     * identidad en el camino crítico de cada mensaje, y una carga que crece con el tráfico del
     * laboratorio.
     */
    @Test
    void el_testigo_se_guarda_y_no_se_pide_uno_por_mensaje() {
        enviarUnAlta();
        int trasElPrimero = IDENTIDAD.canjes();

        enviarUnAlta();

        assertThat(IDENTIDAD.canjes())
                .as(
                        "el testigo vale %d s: pedir otro para el segundo mensaje sobra",
                        IdentidadDePrueba.VIDA_DEL_TESTIGO)
                .isEqualTo(trasElPrimero);
    }

    /**
     * Un {@code ADT^A01} que el motor tiene que acabar escribiendo en el laboratorio.
     *
     * <p>Con {@code MUÑOZ} en el apellido: si algún día la cabecera de autorización rompiera el
     * juego de caracteres de la petición, este test lo vería antes que ninguno.
     */
    private void enviarUnAlta() {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());
        elHis().enviar(MensajesDePrueba.adt("A01", "MSG" + nhc, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1"));
    }

    private String pedir(String ruta) throws Exception {
        HttpRequest peticion = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + ruta))
                .GET()
                .build();
        HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
        assertThat(respuesta.statusCode()).isEqualTo(200);
        return respuesta.body();
    }
}
