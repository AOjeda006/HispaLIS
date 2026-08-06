package es.hispalis.backend.dominio.peticion;

/**
 * Situación de una línea de petición.
 *
 * <p>Son solo dos porque el laboratorio solo necesita distinguir dos cosas: si la línea sigue
 * esperando trabajo o si ya nadie espera nada de ella. «En curso», «recibida» o «en el analizador»
 * son estados del <em>espécimen</em> y del <em>resultado</em>, que son otros agregados; meterlos aquí
 * duplicaría información que ya vive en su sitio y abriría la puerta a que las dos copias se
 * contradigan.
 */
public enum EstadoDeLinea {

    /** Se pidió y sigue en pie: admite espécimen y admite resultado. */
    ACTIVA,

    /**
     * Se retiró antes de poder informarla — típicamente porque su muestra se rechazó y no va a haber
     * otra. Se proyecta como {@code ServiceRequest.status = revoked}.
     */
    ANULADA;

    /** Una línea retirada no puede producir determinaciones: ya se dijo que no se iban a hacer. */
    public boolean admiteResultados() {
        return this == ACTIVA;
    }

    /**
     * Si la línea ya no deja trabajo pendiente por sí misma.
     *
     * <p>Es lo que permite que el invariante del informe cuente una línea anulada como resuelta: lo
     * que ese invariante persigue no es que todo tenga resultado, sino que <strong>nadie siga
     * esperando</strong> algo que el informe da por cerrado.
     */
    public boolean cierraElTrabajo() {
        return this == ANULADA;
    }
}
