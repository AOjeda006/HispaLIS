package es.hispalis.backend.dominio.resultado;

import java.util.List;

/**
 * Puerto de lectura de los rangos de referencia que publica este laboratorio.
 *
 * <p>Se llama catálogo y no repositorio porque no lo es: los rangos son <strong>configuración del
 * laboratorio</strong>, se leen y nunca se escriben desde el sistema. Un repositorio guarda
 * agregados; esto es una tabla de consulta que alguien redacta y el laboratorio publica.
 */
public interface CatalogoDeRangosDeReferencia {

    /**
     * Devuelve todos los rangos definidos para una prueba.
     *
     * <p>Devuelve <strong>todos</strong> y no «el que aplica al paciente» a propósito. Una prueba
     * puede tener un rango por sexo, y la proyección no conoce al paciente: solo tiene el resultado.
     * Publicarlos todos es además lo que FHIR previó —{@code referenceRange} es {@code 0..*} y
     * {@code appliesTo} dice a quién corresponde cada uno—, así que quien lee elige sin que el
     * laboratorio tenga que adivinar por él.
     *
     * @param codigoDePrueba código del catálogo local
     * @return los rangos, o una lista vacía si la prueba no tiene (las cualitativas no lo tienen)
     */
    List<RangoDeReferencia> buscarPorPrueba(String codigoDePrueba);
}
