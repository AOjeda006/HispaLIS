package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * A partir de qué cifra un resultado deja de estar solo fuera de rango y obliga a avisar.
 *
 * <p><strong>No es un {@link RangoDeReferencia}, y confundirlos es el error caro.</strong> Un rango de
 * referencia dice qué es normal; un umbral crítico dice qué es urgente. Un potasio de 6,2 mmol/L está
 * fuera de rango y no es crítico; uno de 7,5 sí. Por eso viven en sitios distintos: los rangos
 * dependen del método y del analizador de cada laboratorio y son configuración suya, mientras que el
 * umbral crítico es el que se pactó con quien recibe la llamada y **se publica en la guía**, con su
 * procedencia dentro del propio concepto.
 *
 * <p><strong>Un umbral sin procedencia no se construye.</strong> Es la única forma de que la regla
 * «los umbrales no se inventan» sobreviva a quien no la haya leído: no hay manera de tener un
 * {@code UmbralCritico} en memoria sin poder decir de dónde salió su cifra.
 *
 * @param codigoDePrueba código del catálogo local al que aplica
 * @param bajo cifra en la que pasa a ser crítico por lo bajo, o {@code null} si no tiene
 * @param alto cifra en la que pasa a ser crítico por lo alto, o {@code null} si no tiene
 * @param unidadUcum unidad en la que están los dos límites; es la que el catálogo declara para la
 *     prueba, y el resultado tiene que venir en ella para poder compararse
 * @param procedencia de dónde sale la cifra, con la referencia concreta
 */
public record UmbralCritico(
        String codigoDePrueba, BigDecimal bajo, BigDecimal alto, String unidadUcum, String procedencia) {

    public UmbralCritico {
        if (codigoDePrueba == null || codigoDePrueba.isBlank()) {
            throw new DatoInvalido("Un umbral crítico sin código de prueba no dice de qué es umbral.");
        }
        if (bajo == null && alto == null) {
            throw new DatoInvalido("El umbral crítico de %s no declara ningún límite: sin cifra no hay nada que avisar."
                    .formatted(codigoDePrueba));
        }
        if (bajo != null && alto != null && bajo.compareTo(alto) > 0) {
            throw new DatoInvalido("El límite crítico bajo de %s (%s) no puede ser mayor que el alto (%s)."
                    .formatted(codigoDePrueba, bajo, alto));
        }
        if (unidadUcum == null || unidadUcum.isBlank()) {
            throw new DatoInvalido("El umbral crítico de %s no dice en qué unidad está: una cifra sola no se puede "
                            .formatted(codigoDePrueba)
                    + "comparar con nada.");
        }
        if (procedencia == null || procedencia.isBlank()) {
            throw new DatoInvalido(
                    "El umbral crítico de %s no dice de dónde sale. Un umbral sin fuente citable no se usa: es el "
                                    .formatted(codigoDePrueba)
                            + "único sitio del sistema donde una cifra inventada se traduce en una llamada que no "
                            + "se hace.");
        }
    }

    /**
     * Dice si un resultado alcanza el umbral.
     *
     * <p><strong>Los límites son inclusivos</strong> y no por descuido: un catálogo que dice «avisa a
     * partir de 6,3» no está diciendo «a partir de 6,31». En la duda se avisa, que es la dirección
     * correcta del error cuando lo que está en juego es una llamada de teléfono.
     *
     * @param valor la cifra medida, o {@code null} si el resultado no es cuantitativo
     * @param unidad la unidad en la que viene la cifra
     * @return {@code true} si el resultado alcanza el límite alto o baja hasta el bajo
     * @throws NoSeSabeSiEsCritico si la unidad no es aquella en la que está el umbral: contestar «no
     *     es crítico» a una comparación que no se ha podido hacer es exactamente la forma de fallar
     *     que este catálogo existe para evitar
     */
    public boolean alcanzaA(BigDecimal valor, String unidad) {
        if (valor == null) {
            // Un resultado cualitativo —un antígeno que da positivo— puede ser urgentísimo, pero no
            // por un umbral: no hay cifra que comparar. Si algún día hay críticos cualitativos, serán
            // otra regla y no esta.
            return false;
        }
        if (!unidadUcum.equals(unidad)) {
            throw new NoSeSabeSiEsCritico(
                    "El umbral crítico de %s está en %s y el resultado viene en %s. No se comparan cifras en unidades "
                                    .formatted(codigoDePrueba, unidadUcum, unidad == null ? "ninguna" : unidad)
                            + "distintas: la respuesta no sería «no es crítico», sería una respuesta inventada.");
        }
        return (alto != null && valor.compareTo(alto) >= 0) || (bajo != null && valor.compareTo(bajo) <= 0);
    }

    /** El límite inferior, si la prueba tiene uno declarado. */
    public Optional<BigDecimal> limiteBajo() {
        return Optional.ofNullable(bajo);
    }

    /** El límite superior, si la prueba tiene uno declarado. */
    public Optional<BigDecimal> limiteAlto() {
        return Optional.ofNullable(alto);
    }
}
