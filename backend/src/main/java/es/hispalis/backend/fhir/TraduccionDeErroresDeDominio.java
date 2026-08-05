package es.hispalis.backend.fhir;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import es.hispalis.backend.dominio.ConflictoDeNegocio;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ErrorDeDominio;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
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
 *   <tr><td>{@link ConflictoDeNegocio}</td><td>409</td><td>está bien formado pero choca con algo que ya existe</td></tr>
 *   <tr><td>{@link ReglaDeNegocioIncumplida}</td><td>422</td><td>la acción no procede — el código que FHIR reserva para sus reglas de negocio</td></tr>
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
    public BaseServerResponseException traducir(Throwable error, RequestDetails peticion) {
        ErrorDeDominio errorDeDominio = buscarErrorDeDominio(error);
        if (errorDeDominio == null) {
            return fallaLaPrecondicion(error, peticion) ? new PreconditionFailedException(error.getMessage()) : null;
        }
        return switch (errorDeDominio) {
            case DatoInvalido e -> new InvalidRequestException(e.getMessage());
            case ConflictoDeNegocio e -> new ResourceVersionConflictException(e.getMessage());
            case ReglaDeNegocioIncumplida e -> new UnprocessableEntityException(e.getMessage());
            default -> new InvalidRequestException(errorDeDominio.getMessage());
        };
    }

    /**
     * Indica si el error es un choque de versión provocado por un {@code If-Match} que ya no vale.
     *
     * <p>HAPI responde {@code 409} a cualquier choque de versión, y para un choque descubierto sin
     * que nadie preguntara es correcto. Pero cuando el cliente <strong>sí preguntó</strong> —mandó
     * {@code If-Match}—, la especificación de FHIR es explícita: lo que ha fallado es una
     * precondición y la respuesta es {@code 412}. La diferencia le importa al cliente, que con un
     * {@code 412} sabe que tiene que releer y reintentar.
     */
    private static boolean fallaLaPrecondicion(Throwable error, RequestDetails peticion) {
        return error instanceof ResourceVersionConflictException
                && peticion != null
                && peticion.getHeader(Constants.HEADER_IF_MATCH) != null;
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
