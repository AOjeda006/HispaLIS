package es.hispalis.integracion.arnes;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Un servidor de identidad de mentira que comprueba la aserción <strong>de verdad</strong>.
 *
 * <p>No devuelve un testigo por devolverlo: se baja el JWKS <em>de la URL que publica el motor</em> y
 * verifica con él la firma RS384 de la aserción de cliente. Si el motor firmara con otra clave, con
 * otro algoritmo, o publicara mal su JWKS, aquí no sale testigo — que es exactamente lo que haría
 * Keycloak.
 *
 * <p>Eso lo convierte en la prueba de que la rotación por URL funciona: el servidor de identidad
 * nunca tiene la clave copiada, la descubre.
 *
 * <p>Guarda además las aserciones recibidas, para que un test pueda mirar de cerca lo que la norma
 * exige de ellas: {@code iss} igual a {@code sub}, {@code aud} igual al {@code token_endpoint},
 * {@code jti} distinto en cada una y una vida que no pasa de cinco minutos.
 */
public final class IdentidadDePrueba implements AutoCloseable {

    /** Cuánto dice durar el testigo que emite. El máximo que recomienda la norma para {@code system/}. */
    public static final long VIDA_DEL_TESTIGO = 300;

    private final HttpServer servidor;
    private final AtomicReference<String> jwksDelCliente = new AtomicReference<>();
    private final List<JWTClaimsSet> aserciones = new ArrayList<>();
    private final List<String> scopesPedidos = new ArrayList<>();
    private final AtomicInteger canjes = new AtomicInteger();

    private IdentidadDePrueba(HttpServer servidor) {
        this.servidor = servidor;
    }

    public static IdentidadDePrueba arrancada() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IdentidadDePrueba identidad = new IdentidadDePrueba(servidor);
            servidor.createContext("/realms/hispalis/.well-known/openid-configuration", identidad::descubrimiento);
            servidor.createContext("/realms/hispalis/protocol/openid-connect/token", identidad::canjear);
            servidor.start();
            return identidad;
        } catch (IOException fallo) {
            throw new UncheckedIOException("No se pudo arrancar el servidor de identidad de prueba", fallo);
        }
    }

    public String emisor() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/realms/hispalis";
    }

    public String puntoDeTestigo() {
        return emisor() + "/protocol/openid-connect/token";
    }

    /**
     * Dónde está el JWKS del cliente.
     *
     * <p>Se dice después de arrancar porque el motor escucha en un puerto aleatorio, igual que en el
     * {@code compose} se configura con el nombre del servicio: el servidor de identidad se baja las
     * claves, no las recibe.
     */
    public void elMotorPublicaSuJwksEn(String url) {
        jwksDelCliente.set(url);
    }

    /** Las aserciones verificadas, en orden. */
    public List<JWTClaimsSet> aserciones() {
        synchronized (aserciones) {
            return List.copyOf(aserciones);
        }
    }

    /** Los {@code scope} pedidos en cada canje, en orden. */
    public List<String> scopesPedidos() {
        synchronized (scopesPedidos) {
            return List.copyOf(scopesPedidos);
        }
    }

    /** Cuántas veces se ha pedido un testigo. Es lo que demuestra que el motor lo guarda. */
    public int canjes() {
        return canjes.get();
    }

    public void olvidarTodo() {
        synchronized (aserciones) {
            aserciones.clear();
        }
        synchronized (scopesPedidos) {
            scopesPedidos.clear();
        }
        canjes.set(0);
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    private void descubrimiento(HttpExchange intercambio) throws IOException {
        responder(
                intercambio,
                200,
                """
                {
                  "issuer": "%1$s",
                  "authorization_endpoint": "%1$s/protocol/openid-connect/auth",
                  "token_endpoint": "%1$s/protocol/openid-connect/token",
                  "jwks_uri": "%1$s/protocol/openid-connect/certs",
                  "token_endpoint_auth_methods_supported": ["private_key_jwt"],
                  "token_endpoint_auth_signing_alg_values_supported": ["RS384"],
                  "scopes_supported": ["system/Patient.crus"]
                }
                """
                        .formatted(emisor()));
    }

    private void canjear(HttpExchange intercambio) throws IOException {
        canjes.incrementAndGet();
        Map<String, String> formulario =
                formulario(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        if (!"client_credentials".equals(formulario.get("grant_type"))) {
            responder(intercambio, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        if (!"urn:ietf:params:oauth:client-assertion-type:jwt-bearer".equals(formulario.get("client_assertion_type"))) {
            responder(intercambio, 400, "{\"error\":\"invalid_client\"}");
            return;
        }

        try {
            JWTClaimsSet reclamaciones = verificar(formulario.get("client_assertion"));
            synchronized (aserciones) {
                aserciones.add(reclamaciones);
            }
            synchronized (scopesPedidos) {
                scopesPedidos.add(formulario.getOrDefault("scope", ""));
            }
            responder(
                    intercambio,
                    200,
                    """
                    {"access_token":"%s","token_type":"Bearer","expires_in":%d,"scope":"%s"}
                    """
                            .formatted(
                                    "testigo-" + UUID.randomUUID(),
                                    VIDA_DEL_TESTIGO,
                                    formulario.getOrDefault("scope", "")));
        } catch (Exception rechazo) {
            responder(
                    intercambio,
                    400,
                    "{\"error\":\"invalid_client\",\"error_description\":\"%s\"}"
                            .formatted(rechazo.getMessage().replace('"', '\'')));
        }
    }

    /**
     * La comprobación completa: firma RS384 contra el JWKS del motor y las reclamaciones obligatorias.
     *
     * <p>{@code aud} tiene que ser este {@code token_endpoint}: una aserción emitida para otro servidor
     * de identidad no puede valer aquí, igual que un testigo emitido para otro servidor de recursos no
     * vale en el laboratorio.
     */
    private JWTClaimsSet verificar(String asercion) throws Exception {
        String jwks = jwksDelCliente.get();
        if (jwks == null) {
            throw new IllegalStateException("no se sabe dónde está el JWKS del cliente");
        }
        DefaultJWTProcessor<SecurityContext> procesador = new DefaultJWTProcessor<>();
        procesador.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS384,
                JWKSourceBuilder.create(URI.create(jwks).toURL()).build()));
        JWTClaimsSet reclamaciones = procesador.process(asercion, null);

        if (!reclamaciones.getIssuer().equals(reclamaciones.getSubject())) {
            throw new IllegalArgumentException("iss y sub tienen que ser el mismo client_id");
        }
        if (!reclamaciones.getAudience().contains(puntoDeTestigo())) {
            throw new IllegalArgumentException("el aud de la aserción no es este token_endpoint");
        }
        if (reclamaciones.getJWTID() == null || reclamaciones.getJWTID().isBlank()) {
            throw new IllegalArgumentException("la aserción no trae jti");
        }
        return reclamaciones;
    }

    private static Map<String, String> formulario(String cuerpo) {
        Map<String, String> campos = new HashMap<>();
        for (String par : Arrays.asList(cuerpo.split("&"))) {
            int igual = par.indexOf('=');
            if (igual > 0) {
                campos.put(
                        URLDecoder.decode(par.substring(0, igual), StandardCharsets.UTF_8),
                        URLDecoder.decode(par.substring(igual + 1), StandardCharsets.UTF_8));
            }
        }
        return campos;
    }

    private static void responder(HttpExchange intercambio, int estado, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        intercambio.sendResponseHeaders(estado, bytes.length);
        try (var salida = intercambio.getResponseBody()) {
            salida.write(bytes);
        }
        intercambio.close();
    }
}
