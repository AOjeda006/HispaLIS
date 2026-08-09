package es.hispalis.backend.dominio.resultado;

/**
 * Situación de un resultado respecto de la revisión facultativa.
 *
 * <p><strong>No es un campo que se guarde:</strong> se deriva de las {@link Validacion} que tiene y
 * de cuántas le hacían falta. Guardarlo aparte permitiría un resultado marcado como validado sin
 * nadie que lo firme —que es justamente el estado que este paso existe para impedir—, y esa
 * combinación imposible es mejor que no se pueda ni escribir.
 */
public enum EstadoDeResultado {

    /** Lo que dio el analizador. Es una cifra medida, no un resultado publicable. */
    PRELIMINAR,

    /**
     * Un crítico que ya tiene una firma y espera la otra.
     *
     * <p>Es un estado propio y no un matiz de {@link #PRELIMINAR}: aquí ya hay una persona que
     * responde de la cifra, y lo que falta es la revisión independiente que exige el invariante de
     * §10. Hacia fuera se proyecta igual —{@code preliminary}, porque no es definitivo—, pero dentro
     * del laboratorio la diferencia es la que separa «nadie lo ha mirado» de «falta el segundo par
     * de ojos», que son dos trabajos pendientes distintos.
     */
    PENDIENTE_DE_SEGUNDA_FIRMA,

    /** Tiene todas las firmas que pedía. Se proyecta como {@code final}. */
    VALIDADO;

    /** Solo lo validado se entrega: un informe no publica lo que le falta una revisión. */
    public boolean esPublicable() {
        return this == VALIDADO;
    }
}
