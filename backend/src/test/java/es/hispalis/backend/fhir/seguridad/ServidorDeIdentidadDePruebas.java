package es.hispalis.backend.fhir.seguridad;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Un servidor de identidad de mentira, para probar la ruta de verdad.
 *
 * <p>Publica {@code .well-known/openid-configuration} y un JWKS con una clave RSA recién generada, y
 * firma testigos con ella. Con eso, el laboratorio hace en los tests <strong>exactamente</strong> lo
 * que hace en producción: descubre los <em>endpoints</em>, se baja las claves y comprueba firma,
 * emisor, caducidad y audiencia con {@code DecodificadorPerezoso}.
 *
 * <p><strong>Por qué esto y no un {@code JwtDecoder} de mentira.</strong> Sustituir el decodificador
 * por uno de test dejaría sin probar justo lo que más cuesta acertar —el descubrimiento, la validación
 * de {@code aud}, el manejo de la identidad caída— y el test pasaría en verde con esa parte rota.
 * Levantar el {@code HttpServer} del JDK cuesta milisegundos y no añade ninguna dependencia.
 *
 * <p>Es <strong>uno para toda la ejecución</strong>: la clave y el puerto no cambian entre clases de
 * test, así que un solo contexto de Spring sirve para todas.
 */
final class ServidorDeIdentidadDePruebas {

    /** La base FHIR por la que se llega a este laboratorio. Un testigo con otro {@code aud} no vale. */
    static final String AUDIENCIA = "https://laboratorio.pruebas.hispalis/fhir";

    private static final ServidorDeIdentidadDePruebas INSTANCIA = new ServidorDeIdentidadDePruebas();

    private final RSAKey clave;
    private final String emisor;

    private ServidorDeIdentidadDePruebas() {
        try {
            this.clave = new RSAKeyGenerator(2048)
                    .keyID("pruebas")
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            this.emisor = "http://localhost:" + http.getAddress().getPort() + "/realms/pruebas";
            http.createContext(
                    "/realms/pruebas/.well-known/openid-configuration",
                    intercambio -> responder(intercambio, descubrimiento()));
            http.createContext(
                    "/realms/pruebas/certs",
                    intercambio -> responder(intercambio, new JWKSet(clave.toPublicJWK()).toString()));
            http.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> http.stop(0)));
        } catch (IOException fallo) {
            throw new UncheckedIOException("No se pudo levantar el servidor de identidad de pruebas", fallo);
        } catch (JOSEException fallo) {
            throw new IllegalStateException("No se pudo generar la clave del servidor de identidad de pruebas", fallo);
        }
    }

    static ServidorDeIdentidadDePruebas elDeSiempre() {
        return INSTANCIA;
    }

    String emisor() {
        return emisor;
    }

    /**
     * Un testigo bien formado, con la audiencia de este laboratorio y media hora de vida.
     *
     * @param sujeto el {@code sub}
     * @param scope los <em>scopes</em> concedidos, separados por espacios
     * @param paciente el paciente del contexto de lanzamiento, o {@code null} si no lo hay
     * @param fhirUser el recurso que representa al usuario, o {@code null}
     */
    String testigo(String sujeto, String scope, String paciente, String fhirUser) {
        return firmar(reclamaciones(sujeto, scope, paciente, fhirUser)
                .audience(List.of(AUDIENCIA))
                .expirationTime(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
                .build());
    }

    /** Un testigo legítimo, bien firmado y sin caducar, pero emitido para otro servidor de recursos. */
    String testigoParaOtroServidor(String sujeto, String scope) {
        return firmar(reclamaciones(sujeto, scope, null, null)
                .audience(List.of("https://otro-hospital.example/fhir"))
                .expirationTime(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
                .build());
    }

    /** Un testigo con todo correcto salvo que caducó hace un minuto. */
    String testigoCaducado(String sujeto, String scope) {
        return firmar(reclamaciones(sujeto, scope, null, null)
                .audience(List.of(AUDIENCIA))
                .expirationTime(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .build());
    }

    private JWTClaimsSet.Builder reclamaciones(String sujeto, String scope, String paciente, String fhirUser) {
        JWTClaimsSet.Builder reclamaciones = new JWTClaimsSet.Builder()
                .issuer(emisor)
                .subject(sujeto)
                .issueTime(Date.from(Instant.now()))
                .claim("scope", scope);
        if (paciente != null) {
            reclamaciones.claim("patient", paciente);
        }
        if (fhirUser != null) {
            reclamaciones.claim("fhirUser", fhirUser);
        }
        return reclamaciones;
    }

    private String firmar(JWTClaimsSet reclamaciones) {
        try {
            SignedJWT testigo = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(clave.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    reclamaciones);
            testigo.sign(new RSASSASigner(clave));
            return testigo.serialize();
        } catch (JOSEException fallo) {
            throw new IllegalStateException("No se pudo firmar el testigo de pruebas", fallo);
        }
    }

    private String descubrimiento() {
        return """
                {
                  "issuer": "%1$s",
                  "authorization_endpoint": "%1$s/protocol/openid-connect/auth",
                  "token_endpoint": "%1$s/protocol/openid-connect/token",
                  "jwks_uri": "%1$s/certs",
                  "token_endpoint_auth_methods_supported": ["private_key_jwt", "client_secret_basic"],
                  "token_endpoint_auth_signing_alg_values_supported": ["RS384", "ES384", "RS256"],
                  "scopes_supported": ["openid", "fhirUser", "launch", "launch/patient", "user/*.rs", "patient/*.rs"],
                  "code_challenge_methods_supported": ["plain", "S256"]
                }
                """
                .formatted(emisor);
    }

    private static void responder(HttpExchange intercambio, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        intercambio.sendResponseHeaders(200, bytes.length);
        try (var salida = intercambio.getResponseBody()) {
            salida.write(bytes);
        }
    }
}
