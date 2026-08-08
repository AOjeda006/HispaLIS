package es.hispalis.backend.infraestructura.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * {@code /fhir/.well-known/smart-configuration}: por dónde empieza cualquier aplicación SMART.
 *
 * <p>Es el único punto que una aplicación necesita saber de memoria. De aquí salen las direcciones de
 * autorización y de testigo —<strong>descubiertas del servidor de identidad, no escritas aquí</strong>—
 * y, sobre todo, las {@code capabilities}: la lista de lo que esta instalación soporta de verdad.
 *
 * <p>Se declara <strong>solo lo que se cumple</strong>. Un {@code capabilities} generoso es peor que
 * uno corto: una aplicación que lee {@code context-ehr-patient} y no lo recibe se rompe en el
 * lanzamiento, con un fallo que parece suyo.
 *
 * <p><strong>Por qué es un servlet propio y no un {@code @RestController}.</strong> La ruta
 * {@code /fhir/*} la sirve el servidor de HAPI, y una regla de servlet exacta gana a una de prefijo:
 * así este documento se publica dentro del espacio de la API FHIR —que es donde SMART dice que
 * cuelga— sin meter dentro del servidor FHIR algo que no es un recurso.
 */
public class DescubrimientoSmart extends HttpServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Lo que esta instalación soporta, comprobado uno a uno.
     *
     * <p>No están, y no es olvido: {@code context-ehr-patient} —el servidor de identidad resuelve el
     * contexto por el usuario y no por el identificador de lanzamiento, así que un profesional no
     * recibe paciente—, {@code permission-offline} —no se conceden testigos de refresco persistentes—
     * y {@code client-confidential-symmetric} —ningún cliente se autentica con secreto compartido—.
     */
    private static final List<String> CAPACIDADES = List.of(
            "launch-ehr",
            "launch-standalone",
            "client-public",
            "client-confidential-asymmetric",
            "sso-openid-connect",
            "context-standalone-patient",
            "permission-patient",
            "permission-user",
            "permission-v1",
            "permission-v2");

    private final transient DescubrimientoOidc oidc;
    private final transient ObjectMapper json = new ObjectMapper();

    public DescubrimientoSmart(DescubrimientoOidc oidc) {
        this.oidc = oidc;
    }

    @Override
    protected void doGet(HttpServletRequest peticion, HttpServletResponse respuesta) throws IOException {
        // El descubrimiento es público y de origen cruzado por definición: una aplicación SMART lo lee
        // desde su propio dominio antes de tener testigo. Aquí sí, y solo aquí: la API FHIR no abre
        // CORS a cualquiera.
        respuesta.setHeader("Access-Control-Allow-Origin", "*");
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Optional<DescubrimientoOidc.Documento> identidad = oidc.documento();
        if (identidad.isEmpty()) {
            respuesta.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            respuesta.setContentType("application/json;charset=UTF-8");
            respuesta
                    .getWriter()
                    .write("{\"error\":\"El servidor de identidad no responde: no se puede publicar el "
                            + "descubrimiento SMART sin saber dónde están sus endpoints.\"}");
            return;
        }

        // «La respuesta es siempre JSON, con independencia del Accept», y todas las URL absolutas.
        respuesta.setStatus(HttpServletResponse.SC_OK);
        respuesta.setContentType("application/json;charset=UTF-8");
        respuesta.getWriter().write(json.writeValueAsString(documento(identidad.get())));
    }

    private ObjectNode documento(DescubrimientoOidc.Documento identidad) {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("issuer", identidad.emisor());
        raiz.put("jwks_uri", identidad.jwks());
        raiz.put("authorization_endpoint", identidad.autorizacion());
        raiz.put("token_endpoint", identidad.testigo());

        poner(raiz, "grant_types_supported", List.of("authorization_code", "client_credentials"));
        poner(raiz, "response_types_supported", List.of("code"));
        poner(raiz, "token_endpoint_auth_methods_supported", identidad.metodosDeAutenticacionDeCliente());
        poner(raiz, "token_endpoint_auth_signing_alg_values_supported", identidad.algoritmosDeFirmaDeCliente());
        poner(raiz, "scopes_supported", identidad.scopesSoportados());

        // Solo `S256`. La norma dice que un servidor NO DEBE soportar `plain`, y el descubrimiento de
        // Keycloak anuncia los dos porque es un ajuste global suyo. Aquí se declara lo que los
        // clientes de este realm tienen impuesto, que es lo que se cumple de verdad.
        poner(raiz, "code_challenge_methods_supported", List.of("S256"));
        poner(raiz, "capabilities", CAPACIDADES);
        return raiz;
    }

    private static void poner(ObjectNode raiz, String nombre, List<String> valores) {
        var array = raiz.putArray(nombre);
        valores.forEach(array::add);
    }
}
