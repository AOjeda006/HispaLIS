package es.hispalis.backend.dominio.especimen;

import es.hispalis.backend.dominio.DatoInvalido;

/**
 * Número de acceso: el código con el que la muestra circula físicamente por el laboratorio.
 *
 * <p>Es lo que une el tubo con el resultado. Va impreso en la etiqueta, lo lee el analizador y es
 * por lo que pregunta cualquiera que busque una muestra en una gradilla. Sin él no hay trazabilidad,
 * y por eso es obligatorio.
 *
 * <p>No se le impone formato más allá de no venir vacío: el número de acceso lo emite el laboratorio
 * y su forma es una decisión operativa que cambia —por serie, por año, por sección— sin que eso deba
 * obligar a tocar el código.
 *
 * @param valor el código, tal y como aparece en la etiqueta
 */
public record NumeroDeAcceso(String valor) {

    public NumeroDeAcceso {
        if (valor == null || valor.isBlank()) {
            throw new DatoInvalido("La muestra necesita número de acceso: es lo que la une con su resultado.");
        }
        valor = valor.strip();
    }

    @Override
    public String toString() {
        return valor;
    }
}
