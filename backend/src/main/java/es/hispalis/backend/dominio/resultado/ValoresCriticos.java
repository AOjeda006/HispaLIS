package es.hispalis.backend.dominio.resultado;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Puerto de lectura de los umbrales que obligan a avisar.
 *
 * <p>Se llama catálogo y no repositorio por lo mismo que {@link CatalogoDeRangosDeReferencia}: se lee
 * y no se escribe nunca desde el sistema. La diferencia está en <strong>dónde vive lo que se lee</strong>:
 * los rangos de referencia son configuración de este laboratorio y viven en su base de datos, mientras
 * que los umbrales críticos son <strong>terminología publicada</strong> —viven en la guía, en
 * {@code CodeSystem/catalogo-pruebas}, con su procedencia en el propio concepto— y se preguntan al
 * servidor de terminología como todo lo demás.
 *
 * <p><strong>Se pregunta por un código a la vez, a propósito.</strong> No hay un método que devuelva
 * «el catálogo de críticos entero»: con uno, lo primero que haría alguien es guardárselo al arrancar,
 * y eso es la lista paralela que prohíbe el invariante 4 — en el sitio donde más caro sale.
 */
public interface ValoresCriticos {

    /**
     * El umbral publicado para una prueba.
     *
     * <p>Devolver vacío significa <strong>una sola cosa</strong>: la autoridad ha contestado y esa
     * prueba no tiene umbral declarado. Cuando no se ha podido preguntar, esto no devuelve vacío
     * —lanza—, porque «no lo sé» y «no tiene» son respuestas distintas y confundirlas es el fallo que
     * este puerto existe para impedir.
     *
     * @param codigoDePrueba código del catálogo local
     * @return el umbral, o vacío si la prueba no tiene ninguno declarado
     * @throws NoSeSabeSiEsCritico si no se ha podido saber
     */
    Optional<UmbralCritico> umbralDe(String codigoDePrueba);

    /**
     * Si un resultado concreto obliga a avisar.
     *
     * @param codigoDePrueba código del catálogo local
     * @param valor la cifra medida, o {@code null} si el resultado no es cuantitativo
     * @param unidadUcum la unidad en la que viene la cifra
     * @throws NoSeSabeSiEsCritico si no se ha podido saber, o si la unidad no permite comparar
     */
    default boolean esCritico(String codigoDePrueba, BigDecimal valor, String unidadUcum) {
        return umbralDe(codigoDePrueba)
                .map(umbral -> umbral.alcanzaA(valor, unidadUcum))
                .orElse(false);
    }
}
