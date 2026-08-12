package es.hispalis.backend.dominio.exportacion;

/**
 * En qué punto está una exportación masiva.
 *
 * <p>Cuatro estados y ninguno de sobra. El que más se discute es {@link #CERRADA}: podría parecer que
 * hacen falta dos —«el cliente la canceló» y «se le pasó el plazo»— y no hacen falta, porque desde el
 * punto de vista del dato son lo mismo: <strong>los ficheros ya no están y no se vuelven a servir</strong>.
 * Quién los retiró es información de operación y vive en el log; convertirla en estado obligaría a
 * mirar dos casillas para contestar la única pregunta que importa aquí, que es si aquello sigue en un
 * disco.
 */
public enum EstadoDeExportacion {

    /** Se está montando. No hay ficheros y el sondeo contesta {@code 202}. */
    EN_CURSO,

    /** Hay manifiesto y hay ficheros. Es el único estado en el que se descarga algo. */
    TERMINADA,

    /** No se pudo montar. Se conserva para poder contestar por qué, no para reintentar. */
    FALLIDA,

    /** Ya no hay nada: se canceló o se le pasó el plazo. El sondeo contesta {@code 404}. */
    CERRADA
}
