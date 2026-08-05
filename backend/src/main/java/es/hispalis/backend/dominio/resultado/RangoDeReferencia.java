package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Entre qué dos cifras se considera normal el resultado de una prueba.
 *
 * <p>Sin él, un resultado es un número suelto. «4,2» no significa nada: es normal para un potasio,
 * alto para una creatinina y bajo para una hemoglobina. Presentar la cifra sin el rango es un error
 * de producto, no una omisión estética — y por eso el criterio de aceptación 10 lo exige junto a la
 * unidad.
 *
 * <p><strong>No es terminología, y por eso no vive en la guía.</strong> Los códigos de prueba son
 * comunes a todo el que hable con este laboratorio y salen del `CodeSystem`; los rangos dependen del
 * método y del analizador de <em>cada</em> laboratorio, y dos laboratorios que usan el mismo código
 * `CREA` publican rangos distintos sin contradecirse. Son configuración del laboratorio, así que
 * viven en su base de datos.
 *
 * @param codigoDePrueba código del catálogo local al que aplica
 * @param sexo sexo al que aplica (`male` o `female`), o vacío si aplica a cualquiera
 * @param bajo límite inferior de la normalidad
 * @param alto límite superior
 * @param unidadUcum unidad en la que están los dos límites; tiene que ser la del resultado
 */
public record RangoDeReferencia(
        String codigoDePrueba, String sexo, BigDecimal bajo, BigDecimal alto, String unidadUcum) {

    public RangoDeReferencia {
        if (bajo == null || alto == null) {
            throw new DatoInvalido("Un rango de referencia necesita sus dos límites.");
        }
        if (bajo.compareTo(alto) > 0) {
            throw new DatoInvalido(
                    "El límite inferior %s no puede ser mayor que el superior %s.".formatted(bajo, alto));
        }
    }

    /** El sexo al que aplica, si el rango no es común a ambos. */
    public Optional<String> sexoAlQueAplica() {
        return Optional.ofNullable(sexo);
    }
}
