package es.hispalis.backend.dominio;

/**
 * Un invariante de negocio incumplido.
 *
 * <p>Es la excepción base del núcleo, y es deliberadamente ajena a HTTP y a FHIR: el dominio no sabe
 * que lo está llamando una API REST. Traducir esto al código de estado y al {@code OperationOutcome}
 * que corresponda es trabajo del borde.
 *
 * <p>No comprobada, según la convención del proyecto: los invariantes de negocio no son condiciones
 * que quien llama pueda recuperar caso por caso.
 */
public abstract class ErrorDeDominio extends RuntimeException {

    protected ErrorDeDominio(String mensaje) {
        super(mensaje);
    }
}
