package es.hispalis.backend.dominio.especimen;

/**
 * En qué situación está una muestra dentro del laboratorio.
 *
 * <p>Los cuatro valores se corresponden con {@code Specimen.status} de FHIR, pero la correspondencia
 * es de <em>traducción</em>, no de identidad: el borde convierte, el dominio no sabe que FHIR existe.
 */
public enum EstadoDeEspecimen {

    /** La muestra está en el laboratorio y sirve para analizar. */
    DISPONIBLE,

    /** Consumida, agotada o extraviada. Existió y ya no está disponible. */
    NO_DISPONIBLE,

    /**
     * <strong>Rechazada.</strong> Llegó hemolizada, coagulada, insuficiente o mal conservada, y por
     * eso <strong>no puede producir ningún resultado</strong>: informar uno sería emitir un dato
     * clínico falso con toda la apariencia de ser bueno.
     */
    RECHAZADA,

    /** Se registró por error y nunca debió existir. */
    ERROR_DE_REGISTRO;

    /**
     * Indica si de esta muestra se puede informar un resultado.
     *
     * <p>Solo la muestra disponible sirve. Una consumida ya no se puede volver a medir y una
     * registrada por error no representa nada.
     */
    public boolean permiteInformarResultados() {
        return this == DISPONIBLE;
    }
}
