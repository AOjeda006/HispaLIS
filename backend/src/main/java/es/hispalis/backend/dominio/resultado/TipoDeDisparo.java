package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import java.util.Arrays;

/**
 * Por qué existe una determinación que apunta a otra.
 *
 * <p>Los tres significan cosas distintas y confundirlos no es un matiz de catalogación: quien lee la
 * historia deduce de ellos <strong>cuál de las dos cifras vale y por qué hay dos</strong>.
 *
 * <ul>
 *   <li>{@link #REFLEJA} — se hizo <em>otra</em> prueba porque esta salió alterada. La decide el
 *       laboratorio con la regla de su catálogo, nunca quien manda el resultado.
 *   <li>{@link #REPETICION} — se repitió <em>la misma</em> prueba con lo mismo. Lo que estaba mal era
 *       la muestra: hemólisis, coágulo, volumen corto.
 *   <li>{@link #REEJECUCION} — se repitió <em>la misma</em> prueba con <em>otro</em> ajuste. Lo que
 *       estaba mal era el analizador: el control de calidad del turno se salió y hubo que recalibrar.
 * </ul>
 *
 * <p>La diferencia entre las dos últimas la fija el propio R5 —«same parameters/settings/solution»
 * frente a «different parameters/settings/solution»—, y es la que separa un problema de la fase
 * preanalítica de uno del analizador. Contarlos juntos taparía cuál de los dos procesos se está
 * yendo.
 *
 * <p>El código FHIR se guarda aquí y no se deduce del nombre: {@code re-run} no es
 * {@code REEJECUCION} en minúsculas, y una traducción por convención de nombres se rompe en silencio
 * el día que alguien añada un valor.
 */
public enum TipoDeDisparo {

    /** {@code reflex}: otra prueba, porque la primera salió alterada. */
    REFLEJA("reflex"),

    /** {@code repeat}: la misma prueba, con lo mismo. La muestra estaba mal. */
    REPETICION("repeat"),

    /** {@code re-run}: la misma prueba, con otro ajuste. El analizador estaba mal. */
    REEJECUCION("re-run");

    private final String codigoFhir;

    TipoDeDisparo(String codigoFhir) {
        this.codigoFhir = codigoFhir;
    }

    /** El código tal y como viaja en {@code Observation.triggeredBy.type}. */
    public String codigoFhir() {
        return codigoFhir;
    }

    /**
     * @param codigo el código recibido en el recurso
     * @throws DatoInvalido si no es ninguno de los tres. No se cae a un valor por defecto: adivinar
     *     aquí convertiría una re-ejecución por control fuera en una repetición por muestra mala.
     */
    public static TipoDeDisparo deCodigoFhir(String codigo) {
        return Arrays.stream(values())
                .filter(tipo -> tipo.codigoFhir.equals(codigo))
                .findFirst()
                .orElseThrow(() -> new DatoInvalido(
                        ("«%s» no dice por qué existe esta determinación. Los valores que FHIR R5 admite en "
                                        + "`triggeredBy.type` son `reflex`, `repeat` y `re-run`.")
                                .formatted(codigo)));
    }
}
