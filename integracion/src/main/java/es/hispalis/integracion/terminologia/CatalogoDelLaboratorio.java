package es.hispalis.integracion.terminologia;

import java.util.Optional;

/**
 * El catálogo de pruebas del laboratorio, visto desde el motor.
 *
 * <p><strong>Es la caja obligatoria del invariante 4, no un {@code Map<String,String>}.</strong> Un
 * emisor no habla el dialecto del laboratorio: el HIS pide en LOINC o en el código que le pasaron, y
 * el analizador informa en el suyo. Traducir eso con una tabla escrita dentro del motor tiene tres
 * consecuencias, y las tres se pagan tarde: la tabla se desvía del catálogo sin que nada avise,
 * nadie prueba el {@code ConceptMap} que la guía publica, y añadir una prueba al catálogo deja de
 * ser un cambio en la guía para pasar a ser un cambio en el código del motor.
 *
 * <p>Por eso esto es un <strong>puerto</strong>. Hoy lo implementa
 * {@code CatalogoLeidoDeLaGuia}, que lee el {@code CodeSystem} y el {@code ConceptMap} que produce
 * SUSHI — igual que hace el generador de datos sintéticos. Cuando exista el servidor de terminología
 * (ítem 33), se sustituye por uno que pregunte con {@code $translate} y <strong>no cambia una sola
 * línea de ningún canal</strong>.
 */
public interface CatalogoDelLaboratorio {

    /** El {@code system} del catálogo local, tal y como lo publica la guía. */
    String SYSTEM = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas";

    /** El {@code system} de LOINC, que es el lenguaje común al que traduce el {@code ConceptMap}. */
    String LOINC = "http://loinc.org";

    /**
     * Traduce un código de fuera al dialecto del laboratorio.
     *
     * <p>Acepta las dos formas en que llega de verdad: el código del propio catálogo —que se
     * comprueba que exista, no se cree a ciegas— y un LOINC, que se traduce recorriendo el
     * {@code ConceptMap} <strong>al revés</strong>. Es el mismo mapa, leído en la otra dirección: no
     * hay una segunda tabla que pueda desviarse de la primera.
     *
     * @param system el {@code system} del código que llegó, o {@code null} si el emisor no lo dice
     * @param codigo el código
     * @return el código del catálogo local, o vacío si no hay forma de saber qué prueba es
     */
    Optional<String> traducirALocal(String system, String codigo);

    /** Los datos de una prueba del catálogo. */
    Optional<PruebaDelCatalogo> buscar(String codigoLocal);

    /**
     * Si el laboratorio acepta ese tipo de muestra, según el {@code ValueSet} que publica la guía.
     *
     * <p>Va aquí y no en el transformador por lo mismo que las pruebas: la lista de tipos de muestra
     * es terminología, y una copia dentro del motor se desviaría del conjunto publicado sin que nada
     * lo notase.
     *
     * @param codigoSnomed el código de {@code SPM-4}
     */
    boolean esTipoDeMuestraConocido(String codigoSnomed);

    /** Cuántas pruebas tiene el catálogo cargado. Sirve para avisar al arrancar si viene vacío. */
    int tamano();
}
