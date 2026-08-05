package es.hispalis.backend.dominio.paciente;

/**
 * Sexo administrativo del paciente.
 *
 * <p>Es un {@code enum} y no una cadena porque es un estado cerrado del dominio. Se llama
 * «administrativo» a propósito: es el que consta en la documentación, y no siempre coincide con el
 * sexo biológico que un rango de referencia de laboratorio necesitaría. Cuando eso importe —los
 * rangos por sexo—, el dato correcto se modela aparte; confundirlos es un error clínico, no de
 * software.
 *
 * <p>{@link #DESCONOCIDO} no es lo mismo que ausencia: significa que se preguntó y no consta.
 */
public enum Sexo {
    MUJER,
    HOMBRE,
    OTRO,
    DESCONOCIDO
}
