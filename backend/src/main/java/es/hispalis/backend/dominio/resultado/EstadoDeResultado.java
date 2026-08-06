package es.hispalis.backend.dominio.resultado;

/**
 * Situación de un resultado respecto de la revisión facultativa.
 *
 * <p><strong>No es un campo que se guarde:</strong> se deriva de si el resultado tiene
 * {@link Validacion} o no. Guardarlo aparte permitiría un resultado marcado como validado sin nadie
 * que lo firme —que es justamente el estado que este paso existe para impedir—, y esa combinación
 * imposible es mejor que no se pueda ni escribir.
 */
public enum EstadoDeResultado {

    /** Lo que dio el analizador. Es una cifra medida, no un resultado publicable. */
    PRELIMINAR,

    /** Una persona lo ha revisado y responde de él. Se proyecta como {@code final}. */
    VALIDADO;

    /** Solo lo validado se entrega: un informe no publica lo que nadie ha mirado. */
    public boolean esPublicable() {
        return this == VALIDADO;
    }
}
