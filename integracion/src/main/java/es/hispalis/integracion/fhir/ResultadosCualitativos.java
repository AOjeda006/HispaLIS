package es.hispalis.integracion.fhir;

/**
 * El vocabulario de los resultados cualitativos del laboratorio, visto desde el motor.
 *
 * <p>Repite lo que declara {@code ig/input/fsh/vocabulary/ResultadosCualitativos.fsh}, igual que
 * {@link SistemasDeIdentificador} repite los identificadores y por la misma razón: el motor construye
 * recursos FHIR y no puede depender de que alguien haya arrancado la guía.
 *
 * <p>Lo que aquí importa no es la constante, es <strong>el nombre de v2</strong>. En un {@code OBX}
 * codificado, el vocabulario del valor viaja en el tercer componente de {@code OBX-5} como un nombre
 * local —{@code 99HISPCUAL}—, y traducirlo a la URI canónica es lo único que convierte «una cadena
 * que pone POS» en un código. De ese código depende que una Legionella positiva se declare.
 */
public final class ResultadosCualitativos {

    /** El {@code system} del vocabulario, tal y como lo publica la guía. */
    public static final String SYSTEM = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/resultados-cualitativos";

    /**
     * Cómo se llama ese vocabulario en {@code HL7 v2}.
     *
     * <p>Los nombres locales empiezan por {@code 99} por convención de la tabla 0396: es el tramo
     * reservado a los vocabularios que no publica HL7. {@code 99HISPALIS} es el catálogo de pruebas
     * y este es el de los valores — dos vocabularios distintos que aparecen en campos distintos del
     * mismo segmento, y confundirlos guardaría un resultado como si fuera una prueba.
     */
    public static final String NOMBRE_EN_V2 = "99HISPCUAL";

    private ResultadosCualitativos() {
        // Solo constantes.
    }
}
