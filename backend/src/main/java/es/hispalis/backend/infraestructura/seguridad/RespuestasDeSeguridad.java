package es.hispalis.backend.infraestructura.seguridad;

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Lo que contesta el filtro cuando no deja pasar: un {@code OperationOutcome}, nunca un cuerpo vacío.
 *
 * <p>Spring Security contesta por omisión un {@code 401} pelado. Aquí eso sería incumplir el
 * invariante 8 del proyecto en el sitio más visible de todos: la primera respuesta que ve un cliente
 * nuevo. Un cliente FHIR sabe leer un {@code OperationOutcome} y enseñarle a alguien qué ha pasado;
 * un cuerpo vacío con un {@code 401} solo se puede depurar con el navegador abierto.
 *
 * <p>La cabecera {@code WWW-Authenticate} tampoco es decorativa: es la que dice que lo que falta es
 * un testigo <em>Bearer</em>, y sin ella un cliente no tiene forma de distinguir «no me he
 * identificado» de «este servidor no me quiere».
 */
class RespuestasDeSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String TIPO_FHIR = "application/fhir+json;charset=UTF-8";

    private final FhirContext contexto;

    RespuestasDeSeguridad(FhirContext contexto) {
        this.contexto = contexto;
    }

    @Override
    public void commence(HttpServletRequest peticion, HttpServletResponse respuesta, AuthenticationException fallo)
            throws IOException {
        respuesta.setHeader("WWW-Authenticate", "Bearer realm=\"HispaLIS\"");
        escribir(
                respuesta,
                HttpServletResponse.SC_UNAUTHORIZED,
                IssueType.LOGIN,
                "Esta API exige un testigo de acceso SMART on FHIR. Consulta "
                        + "/fhir/.well-known/smart-configuration para saber dónde pedirlo.");
    }

    @Override
    public void handle(HttpServletRequest peticion, HttpServletResponse respuesta, AccessDeniedException fallo)
            throws IOException {
        escribir(
                respuesta,
                HttpServletResponse.SC_FORBIDDEN,
                IssueType.FORBIDDEN,
                "El testigo es válido pero no alcanza a lo que se ha pedido.");
    }

    private void escribir(HttpServletResponse respuesta, int estado, IssueType tipo, String texto) throws IOException {
        OperationOutcome resultado = new OperationOutcome();
        resultado.addIssue().setSeverity(IssueSeverity.ERROR).setCode(tipo).setDiagnostics(texto);

        respuesta.setStatus(estado);
        respuesta.setContentType(TIPO_FHIR);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getWriter().write(contexto.newJsonParser().encodeResourceToString(resultado));
    }
}
