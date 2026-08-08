package es.hispalis.backend.infraestructura.seguridad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lee el documento de descubrimiento OIDC del servidor de identidad, y lo lee tarde.
 *
 * <p><strong>No se cablea ningún <em>endpoint</em>.</strong> Lo único configurado es el emisor; de
 * ahí salen la URL de autorización, la del testigo, la del JWKS y los métodos de autenticación de
 * cliente que el servidor soporta de verdad. Cablearlos convertiría un cambio de configuración del
 * servidor de identidad en un error en ejecución de este.
 *
 * <p><strong>Y se lee en la primera petición, no al arrancar.</strong> Un laboratorio que no levanta
 * porque el servidor de identidad tarda diez segundos más que él es un laboratorio que se cae por una
 * carrera de arranque. Lo que ocurre sin identidad disponible es que no se puede autenticar a nadie
 * —y la API contesta {@code 401}, que es lo correcto—, no que el proceso no exista.
 *
 * <p>Una vez leído se guarda: el documento cambia con la configuración del <em>realm</em>, no con las
 * peticiones. Si la lectura falla no se guarda nada, así que la siguiente vuelve a intentarlo.
 */
public class DescubrimientoOidc {

    private static final Logger log = LoggerFactory.getLogger(DescubrimientoOidc.class);

    private final PropiedadesDeSeguridad propiedades;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Documento> leido = new AtomicReference<>();

    public DescubrimientoOidc(PropiedadesDeSeguridad propiedades) {
        this.propiedades = propiedades;
        this.http = HttpClient.newBuilder()
                .connectTimeout(propiedades.tiempoDeEspera())
                .build();
    }

    /**
     * Lo que el servidor de identidad declara de sí mismo.
     *
     * @param emisor el {@code iss} que llevarán los testigos
     * @param autorizacion dónde manda el navegador al usuario
     * @param testigo dónde se canjea el código y se piden los testigos de sistema
     * @param jwks dónde están las claves con las que se comprueba la firma
     * @param metodosDeAutenticacionDeCliente cómo puede identificarse un cliente confidencial
     * @param algoritmosDeFirmaDeCliente con qué puede firmar su aserción
     * @param scopesSoportados los que el <em>realm</em> tiene definidos. Se pasan tal cual al
     *     documento de SMART: quien sabe qué <em>scopes</em> existen es quien los concede, y una
     *     lista escrita en el laboratorio se quedaría atrás el día que alguien añada uno
     */
    public record Documento(
            String emisor,
            String autorizacion,
            String testigo,
            String jwks,
            List<String> metodosDeAutenticacionDeCliente,
            List<String> algoritmosDeFirmaDeCliente,
            List<String> scopesSoportados) {}

    /** El documento, leyéndolo si hace falta. Vacío si no hay emisor configurado o no se pudo leer. */
    public Optional<Documento> documento() {
        if (!propiedades.hayEmisor()) {
            return Optional.empty();
        }
        Documento guardado = leido.get();
        if (guardado != null) {
            return Optional.of(guardado);
        }
        return leer().map(documento -> {
            leido.set(documento);
            return documento;
        });
    }

    /** La URL del JWKS, que es lo que necesita el validador de firmas. */
    public Optional<String> jwks() {
        return documento().map(Documento::jwks);
    }

    private Optional<Documento> leer() {
        String url = propiedades.emisor().replaceAll("/+$", "") + "/.well-known/openid-configuration";
        try {
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
                    .timeout(propiedades.tiempoDeEspera())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                log.warn(
                        "El servidor de identidad contestó {} a {}. Nadie podrá autenticarse.",
                        respuesta.statusCode(),
                        url);
                return Optional.empty();
            }
            return Optional.of(interpretar(json.readTree(respuesta.body())));
        } catch (IOException fallo) {
            log.warn(
                    "No se pudo leer el descubrimiento OIDC en {}: {}. Nadie podrá autenticarse.",
                    url,
                    fallo.getMessage());
            return Optional.empty();
        } catch (InterruptedException interrupcion) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Documento interpretar(JsonNode raiz) {
        return new Documento(
                texto(raiz, "issuer"),
                texto(raiz, "authorization_endpoint"),
                texto(raiz, "token_endpoint"),
                texto(raiz, "jwks_uri"),
                lista(raiz, "token_endpoint_auth_methods_supported"),
                lista(raiz, "token_endpoint_auth_signing_alg_values_supported"),
                lista(raiz, "scopes_supported"));
    }

    private static String texto(JsonNode raiz, String campo) {
        JsonNode valor = raiz.get(campo);
        return valor == null || valor.isNull() ? null : valor.asText();
    }

    private static List<String> lista(JsonNode raiz, String campo) {
        JsonNode valor = raiz.get(campo);
        if (valor == null || !valor.isArray()) {
            return List.of();
        }
        List<String> valores = new ArrayList<>(valor.size());
        valor.forEach(elemento -> valores.add(elemento.asText()));
        return List.copyOf(valores);
    }
}
