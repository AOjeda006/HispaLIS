package es.hispalis.integracion.infraestructura.seguridad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El testigo {@code system/} del motor: cómo se consigue y cuánto dura.
 *
 * <p>Es SMART Backend Services (D5). No hay usuario, no hay navegador y no hay secreto compartido: el
 * motor firma con su clave privada una <strong>aserción de cliente</strong> y la canjea por un testigo
 * de acceso. El laboratorio nunca conoce esa clave; solo su parte pública, que se baja del JWKS que
 * publica el propio motor.
 *
 * <p>Cuatro detalles de la norma que no son opcionales y que se incumplen con facilidad:
 *
 * <ul>
 *   <li>La aserción vive <strong>cinco minutos como mucho</strong>. Es una credencial de un solo uso
 *       viajando por la red: cuanto menos valga si se intercepta, mejor.
 *   <li>El {@code jti} es <strong>único por aserción</strong>. Es lo que permite al servidor de
 *       identidad rechazar una repetición, y por eso se genera nuevo cada vez y no se cachea con el
 *       testigo.
 *   <li>El {@code aud} de la aserción es el <strong>{@code token_endpoint}</strong>, no el
 *       laboratorio. La aserción se la presenta el motor al servidor de identidad; el testigo que sale
 *       de ahí es el que va dirigido al laboratorio.
 *   <li><strong>No hay testigo de refresco.</strong> Cuando caduca se pide otro con una aserción
 *       nueva, que es más barato y más seguro que guardar una credencial de larga vida.
 * </ul>
 *
 * <p>El testigo sí se guarda, y se renueva con {@link PropiedadesDeIdentidad#margen()} de antelación.
 * Sin margen, una petición lanzada un instante antes del vencimiento llega con un testigo muerto y el
 * canal la manda a la bandeja de errores por algo que no tiene nada que ver con el mensaje.
 */
public class TestigoDeSistema {

    private static final Logger log = LoggerFactory.getLogger(TestigoDeSistema.class);

    private static final String TIPO_DE_ASERCION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    /** El máximo que admite la norma. No se apura: cinco minutos ya son de sobra para un canje. */
    private static final Duration VIDA_DE_LA_ASERCION = Duration.ofMinutes(5);

    private final PropiedadesDeIdentidad propiedades;
    private final ClaveDelMotor clave;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Concedido> guardado = new AtomicReference<>();
    private final AtomicReference<String> puntoDeTestigo = new AtomicReference<>();

    private record Concedido(String testigo, Instant caducaEn) {}

    public TestigoDeSistema(PropiedadesDeIdentidad propiedades, ClaveDelMotor clave) {
        this.propiedades = propiedades;
        this.clave = clave;
        this.http = HttpClient.newBuilder()
                .connectTimeout(propiedades.tiempoDeEspera())
                .build();
    }

    /**
     * El testigo con el que firmar la siguiente petición, pidiéndolo si hace falta.
     *
     * <p>Devuelve vacío cuando no se ha podido conseguir. Quien llama decide qué hacer con eso: aquí
     * no se lanza excepción porque un servidor de identidad caído es una condición temporal, y el
     * canal ya sabe mandar a la bandeja de errores lo que no pudo escribir.
     */
    public Optional<String> testigo() {
        Concedido vigente = guardado.get();
        if (vigente != null && Instant.now().isBefore(vigente.caducaEn())) {
            return Optional.of(vigente.testigo());
        }
        return pedirlo();
    }

    /**
     * Tira el testigo guardado.
     *
     * <p>Lo llama el interceptor cuando el laboratorio contesta {@code 401}. Un testigo puede morir
     * antes de su {@code exp} —una rotación de claves, una sesión revocada— y sin esto el motor
     * seguiría presentando el mismo testigo muerto hasta que venciera su reloj.
     */
    public void olvidarlo() {
        guardado.set(null);
    }

    private synchronized Optional<String> pedirlo() {
        Concedido vigente = guardado.get();
        if (vigente != null && Instant.now().isBefore(vigente.caducaEn())) {
            // Otra petición lo renovó mientras esta esperaba el cerrojo.
            return Optional.of(vigente.testigo());
        }
        Optional<String> punto = puntoDeTestigo();
        if (punto.isEmpty()) {
            return Optional.empty();
        }
        try {
            String cuerpo = formulario(punto.get());
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(punto.get()))
                    .timeout(propiedades.tiempoDeEspera())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                // El cuerpo de un error de OAuth2 lleva `error` y `error_description`, nunca datos
                // clínicos: se registra entero porque es lo único que explica un `invalid_scope`.
                log.error(
                        "El servidor de identidad rechazó la petición de testigo del motor ({}): {}",
                        respuesta.statusCode(),
                        respuesta.body());
                return Optional.empty();
            }
            return Optional.of(guardar(json.readTree(respuesta.body())));
        } catch (IOException fallo) {
            log.error("No se pudo pedir el testigo de sistema en {}: {}", punto.get(), fallo.getMessage());
            return Optional.empty();
        } catch (InterruptedException interrupcion) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private String guardar(JsonNode concesion) {
        String testigo = concesion.get("access_token").asText();
        // `expires_in` es el dato que manda; si faltara, se supone lo mínimo razonable en vez de
        // suponer que dura mucho — equivocarse por arriba deja al motor presentando testigos muertos.
        long segundos =
                concesion.has("expires_in") ? concesion.get("expires_in").asLong() : 60;
        Instant caducaEn = Instant.now().plusSeconds(segundos).minus(propiedades.margen());
        guardado.set(new Concedido(testigo, caducaEn));
        log.info("Testigo de sistema obtenido para el cliente {}; vale {} s.", propiedades.cliente(), segundos);
        return testigo;
    }

    private String formulario(String punto) {
        return "grant_type=client_credentials"
                + "&scope=" + codificar(propiedades.scopes())
                + "&client_assertion_type=" + codificar(TIPO_DE_ASERCION)
                + "&client_assertion=" + codificar(asercion(punto));
    }

    /**
     * La aserción de cliente: el motor afirmando quién es, firmado con su clave.
     *
     * <p>{@code iss} y {@code sub} son los dos el {@code client_id}. No es redundancia: la norma lo
     * exige así justamente para que una aserción no pueda usarse para hablar en nombre de otro.
     */
    private String asercion(String puntoDeTestigo) {
        try {
            Instant ahora = Instant.now();
            JWTClaimsSet reclamaciones = new JWTClaimsSet.Builder()
                    .issuer(propiedades.cliente())
                    .subject(propiedades.cliente())
                    .audience(puntoDeTestigo)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(ahora))
                    .expirationTime(Date.from(ahora.plus(VIDA_DE_LA_ASERCION)))
                    .build();

            SignedJWT firmada = new SignedJWT(
                    new JWSHeader.Builder(ClaveDelMotor.ALGORITMO)
                            .keyID(clave.paraFirmar().getKeyID())
                            .build(),
                    reclamaciones);
            firmada.sign(new RSASSASigner(clave.paraFirmar()));
            return firmada.serialize();
        } catch (JOSEException fallo) {
            throw new IllegalStateException("No se pudo firmar la aserción de cliente del motor", fallo);
        }
    }

    /**
     * Dónde se canjea, descubierto del <em>realm</em> y no cableado.
     *
     * <p>Se lee una vez y se guarda: cambia con la configuración del servidor de identidad, no con las
     * peticiones. Si la lectura falla no se guarda nada y la siguiente vuelve a intentarlo.
     */
    private Optional<String> puntoDeTestigo() {
        String yaLeido = puntoDeTestigo.get();
        if (yaLeido != null) {
            return Optional.of(yaLeido);
        }
        String url = propiedades.emisor().replaceAll("/+$", "") + "/.well-known/openid-configuration";
        try {
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                    .timeout(propiedades.tiempoDeEspera())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                log.error("El servidor de identidad contestó {} a {}.", respuesta.statusCode(), url);
                return Optional.empty();
            }
            String punto = json.readTree(respuesta.body()).get("token_endpoint").asText();
            puntoDeTestigo.set(punto);
            return Optional.of(punto);
        } catch (IOException fallo) {
            log.error("No se pudo leer el descubrimiento OIDC en {}: {}", url, fallo.getMessage());
            return Optional.empty();
        } catch (InterruptedException interrupcion) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor == null ? "" : valor, StandardCharsets.UTF_8);
    }
}
