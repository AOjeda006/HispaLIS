package es.hispalis.backend.dominio.edo;

import es.hispalis.backend.dominio.DatoInvalido;
import java.time.Duration;
import java.time.Instant;

/**
 * Qué enfermedad declara una prueba, con qué resultado y en cuánto tiempo.
 *
 * <p>Las tres mitades van juntas porque por separado no dicen nada útil. Una prueba que declara sin
 * decir con qué valor declararía también los negativos —y llenar el sistema de vigilancia de casos
 * que luego hay que retirar es peor que no declarar nada—; un criterio de positividad sin enfermedad
 * no le dice a Salud Pública de qué se le está avisando; y una obligación sin plazo no se puede
 * incumplir, que es lo mismo que no tenerla.
 *
 * <p><strong>El plazo es de la enfermedad, no de la prueba</strong>, y por eso se lee de
 * {@code CodeSystem/enfermedades-edo} y no del catálogo local: una legionelosis es urgente la detecte
 * la técnica que la detecte. Aquí llegan ya juntos porque quien decide no tiene por qué saber de
 * cuántos sitios salió cada dato.
 *
 * <p><strong>Todo son códigos.</strong> No hay aquí ni un dato de la persona, y no es una omisión que
 * haya que recordar mantener: es que la regla no tiene forma de mirarlos aunque quisiera.
 *
 * @param codigoDePrueba la prueba del catálogo local que puede detectarla ({@code LEGIOAG})
 * @param codigoDeEnfermedad la enfermedad de {@code CodeSystem/enfermedades-edo}
 *     ({@code LEGIONELOSIS})
 * @param nombreDeLaEnfermedad su nombre en español, tal y como lo publica la guía
 * @param resultadoQueDeclara el valor cualitativo que dispara la declaración ({@code POS})
 * @param modalidad urgente u ordinaria, según la normativa
 * @param plazo de cuánto tiempo dispone el laboratorio desde que el resultado queda validado
 */
public record ReglaDeDeclaracion(
        String codigoDePrueba,
        String codigoDeEnfermedad,
        String nombreDeLaEnfermedad,
        String resultadoQueDeclara,
        ModalidadDeDeclaracion modalidad,
        Duration plazo) {

    public ReglaDeDeclaracion {
        if (codigoDePrueba == null || codigoDePrueba.isBlank()) {
            throw new DatoInvalido("Una regla de declaración sin prueba no dice qué la dispara.");
        }
        if (codigoDeEnfermedad == null || codigoDeEnfermedad.isBlank()) {
            throw new DatoInvalido(
                    "La regla de declaración de %s no dice qué enfermedad se declara.".formatted(codigoDePrueba));
        }
        // Se comprueba aquí y no se deja para la base de datos, y esa diferencia costó un fallo: sin
        // esta línea, un nombre ausente pasaba el dominio entero y reventaba contra el `NOT NULL` de
        // la V15 dentro del bucle del notificador, que reintenta cada cinco segundos. El síntoma era
        // un log repitiendo el nombre de una columna, ninguna declaración abierta, y nada que
        // señalase al catálogo. Un aviso que nombra el concepto vale mucho más que ese.
        if (nombreDeLaEnfermedad == null || nombreDeLaEnfermedad.isBlank()) {
            throw new DatoInvalido(
                    ("La regla de declaración de %s no dice cómo se llama la enfermedad %s. El nombre lo publica "
                                    + "`CodeSystem/enfermedades-edo` en el `display` de su concepto.")
                            .formatted(codigoDePrueba, codigoDeEnfermedad));
        }
        if (resultadoQueDeclara == null || resultadoQueDeclara.isBlank()) {
            throw new DatoInvalido(
                    ("La regla de declaración de %s no dice con qué resultado se declara. Sin criterio se "
                                    + "declararía también un negativo, y eso no es un exceso de celo: es meter "
                                    + "en el sistema de vigilancia casos que hay que retirar después.")
                            .formatted(codigoDePrueba));
        }
        if (modalidad == null || plazo == null || plazo.isNegative() || plazo.isZero()) {
            throw new DatoInvalido(
                    ("La regla de declaración de %s no dice en cuánto tiempo hay que declarar. Una obligación "
                                    + "sin plazo no se puede incumplir, y una que no se puede incumplir no se "
                                    + "vigila: es lo mismo que no tenerla.")
                            .formatted(codigoDePrueba));
        }
    }

    /** Si un valor cualitativo concreto es el que obliga a declarar. */
    public boolean laDispara(String codigoDelValor) {
        return resultadoQueDeclara.equals(codigoDelValor);
    }

    /**
     * Cuándo se agota la ventana legal, contada desde que el resultado quedó validado.
     *
     * <p>Desde la validación y no desde que al notificador le toca el turno: si el laboratorio arrastra
     * una cola de dos horas, el plazo no se estira dos horas — la obligación nació cuando alguien
     * respondió de la cifra.
     */
    public Instant venceDesde(Instant validadoEn) {
        return validadoEn.plus(plazo);
    }
}
