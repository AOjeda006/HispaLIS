package es.hispalis.backend.dominio.edo;

/**
 * Con cuánta prisa hay que declarar, que en la normativa no es un adjetivo sino dos regímenes.
 *
 * <p>Viene del catálogo —{@code CodeSystem/enfermedades-edo}, propiedad {@code modalidad-declaracion}—
 * y no se decide aquí. Lo que sí vive aquí es la consecuencia visible: una urgente sale como
 * {@code stat} en el recurso, una ordinaria como {@code routine}, y eso es lo que permite ordenar una
 * bandeja de declaraciones sin consultar el catálogo fila a fila.
 */
public enum ModalidadDeDeclaracion {

    /** Sin esperar, en cuanto el laboratorio confirma. Un solo caso puede obligar a intervenir. */
    URGENTE,

    /** Dentro de la semana epidemiológica. La vigilancia es de tendencia, no de alerta. */
    ORDINARIA;

    /**
     * La misma modalidad, leída de un código del catálogo.
     *
     * @throws IllegalArgumentException si el código no es ninguno de los dos, que es un catálogo mal
     *     publicado y no una entrada de usuario
     */
    public static ModalidadDeDeclaracion de(String codigo) {
        return valueOf(codigo);
    }
}
