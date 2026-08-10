package es.hispalis.backend.dominio.edo;

import java.util.Optional;

/**
 * Puerto de lectura del catálogo de enfermedades de declaración obligatoria.
 *
 * <p>Es terminología publicada, igual que los umbrales críticos y las reglas reflejas: vive en
 * {@code CodeSystem/catalogo-pruebas}, en las propiedades {@code enfermedad-edo} y
 * {@code resultado-que-declara} de cada concepto, y se pregunta al servidor de terminología. Que la
 * relación de EDO sea una obligación legal es exactamente el motivo de que no pueda estar en el
 * código: cuando la Consejería añada una enfermedad, esto tiene que ser publicar un catálogo, no
 * desplegar un laboratorio.
 *
 * <p><strong>Se pregunta por un código a la vez</strong>, por lo mismo que en
 * {@code ValoresCriticos}: un método que devolviera «la lista entera de EDO» acabaría cacheado al
 * arrancar, y eso es la lista paralela que prohíbe el invariante 4.
 */
public interface CatalogoEdo {

    /**
     * Qué declara una prueba, si es que declara algo.
     *
     * <p>Vacío es lo normal: casi ninguna prueba de un catálogo es de declaración obligatoria.
     *
     * <p><strong>Vacío también cuando no se ha podido preguntar</strong>, y aquí sí conviene decir
     * por qué eso no abre un agujero. Callarse una declaración obligatoria sería grave —es una
     * obligación legal—, pero este puerto solo se consulta al validar un resultado, y validar ya se
     * niega a seguir cuando la terminología no contesta: {@code ValoresCriticos.umbralDe} lanza
     * {@code NoSeSabeSiEsCritico} y la operación devuelve {@code 503}. Es decir, no existe el camino
     * «se validó y no se preguntó»: si el catálogo no está, no hay validación de la que declarar.
     *
     * @param codigoDePrueba código del catálogo local
     */
    Optional<ReglaDeDeclaracion> declaracionDe(String codigoDePrueba);
}
