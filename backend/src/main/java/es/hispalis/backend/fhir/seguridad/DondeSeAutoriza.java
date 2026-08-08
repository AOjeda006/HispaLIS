package es.hispalis.backend.fhir.seguridad;

import java.util.Optional;

/**
 * Las direcciones del servidor de autorización, para poder declararlas en el
 * {@code CapabilityStatement}.
 *
 * <p>Existe para que el borde FHIR no tenga que conocer al servidor de identidad. Lo que la
 * declaración de conformidad necesita son dos URL; de dónde salen —de un descubrimiento OIDC, de una
 * configuración, de un servidor distinto— no es asunto suyo.
 *
 * <p>Devuelve vacío cuando no hay autorización que declarar. Un {@code CapabilityStatement} que
 * anuncia SMART en un servidor abierto miente, y lo hace en el sitio donde un cliente no va a dudar.
 */
@FunctionalInterface
public interface DondeSeAutoriza {

    /**
     * @param autorizacion a dónde se manda al usuario a identificarse
     * @param testigo dónde se canjea el código o se piden los testigos de sistema
     */
    record Direcciones(String autorizacion, String testigo) {}

    Optional<Direcciones> direcciones();
}
