package es.hispalis.backend.fhir;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import es.hispalis.backend.dominio.ConflictoDeNegocio;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ErrorDeDominio;
import org.springframework.stereotype.Component;

/**
 * Traduce los errores del núcleo al código HTTP que les corresponde.
 *
 * <p>El dominio lanza excepciones que no saben nada de HTTP —es su virtud, no una carencia—, y HAPI
 * convierte en {@code 500} cualquier excepción que no reconozca. Un invariante de negocio incumplido
 * <strong>no es un fallo del servidor</strong>: es una respuesta legítima que el cliente necesita
 * poder distinguir y sobre la que puede actuar.
 *
 * <p>Vive aquí, en el borde, y no en el dominio ni repetido en cada proveedor: es el único sitio que
 * conoce a la vez las dos cosas que hay que casar. El {@code OperationOutcome} lo compone HAPI a
 * partir de la excepción que se devuelve, con el mensaje del dominio dentro.
 *
 * <table>
 *   <caption>Correspondencia</caption>
 *   <tr><th>Error de dominio</th><th>HTTP</th><th>Por qué</th></tr>
 *   <tr><td>{@link DatoInvalido}</td><td>400</td><td>lo que llegó está mal formado</td></tr>
 *   <tr><td>{@link ConflictoDeNegocio}</td><td>409</td><td>está bien formado pero choca con el estado actual</td></tr>
 * </table>
 */
@Interceptor
@Component
public class TraduccionDeErroresDeDominio {

    /** Tope al recorrer la cadena de causas: una cadena cíclica no debe colgar la respuesta. */
    private static final int PROFUNDIDAD_MAXIMA = 10;

    /**
     * @param error lo que sea que se haya lanzado al atender la petición
     * @return la excepción con la que HAPI debe responder, o {@code null} para no intervenir y
     *     dejar que siga su curso — que es lo correcto con todo lo que no sea del dominio
     */
    @Hook(Pointcut.SERVER_PRE_PROCESS_OUTGOING_EXCEPTION)
    public BaseServerResponseException traducir(Throwable error) {
        ErrorDeDominio errorDeDominio = buscarErrorDeDominio(error);
        if (errorDeDominio == null) {
            return null;
        }
        return switch (errorDeDominio) {
            case DatoInvalido e -> new InvalidRequestException(e.getMessage());
            case ConflictoDeNegocio e -> new ResourceVersionConflictException(e.getMessage());
            default -> new InvalidRequestException(errorDeDominio.getMessage());
        };
    }

    /**
     * Busca un error de dominio en la cadena de causas, no solo en la excepción de arriba.
     *
     * <p>Hace falta porque HAPI <strong>ya ha envuelto</strong> lo que lanzó el proveedor cuando
     * llega aquí: lo convierte en un {@code InternalErrorException} con el prefijo
     * {@code HAPI-0389: Failed to call access method}. Mirar solo el tipo de la excepción recibida
     * no encuentra nunca nada, y el síntoma es un {@code 500} en vez del código correcto — con el
     * mensaje del dominio dentro, lo que hace parecer que el interceptor funciona.
     */
    private static ErrorDeDominio buscarErrorDeDominio(Throwable error) {
        Throwable actual = error;
        for (int profundidad = 0; actual != null && profundidad < PROFUNDIDAD_MAXIMA; profundidad++) {
            if (actual instanceof ErrorDeDominio errorDeDominio) {
                return errorDeDominio;
            }
            actual = actual.getCause();
        }
        return null;
    }
}
