package es.hispalis.backend.dominio.edo;

import es.hispalis.backend.dominio.DatoInvalido;

/**
 * Qué enfermedad declara una prueba, y con qué resultado.
 *
 * <p>Las dos mitades van juntas porque por separado no dicen nada útil. Una prueba que declara sin
 * decir con qué valor declararía también los negativos —y llenar el sistema de vigilancia de casos
 * que luego hay que retirar es peor que no declarar nada—; un criterio de positividad sin enfermedad
 * no le dice a Salud Pública de qué se le está avisando.
 *
 * <p><strong>Todo son códigos.</strong> No hay aquí ni un dato de la persona, y no es una omisión que
 * haya que recordar mantener: es que la regla no tiene forma de mirarlos aunque quisiera.
 *
 * @param codigoDePrueba la prueba del catálogo local que puede detectarla ({@code LEGIOAG})
 * @param codigoDeEnfermedad la enfermedad de {@code CodeSystem/enfermedades-edo}
 *     ({@code LEGIONELOSIS})
 * @param nombreDeLaEnfermedad su nombre en español, tal y como lo publica la guía
 * @param resultadoQueDeclara el valor cualitativo que dispara la declaración ({@code POS})
 */
public record ReglaDeDeclaracion(
        String codigoDePrueba, String codigoDeEnfermedad, String nombreDeLaEnfermedad, String resultadoQueDeclara) {

    public ReglaDeDeclaracion {
        if (codigoDePrueba == null || codigoDePrueba.isBlank()) {
            throw new DatoInvalido("Una regla de declaración sin prueba no dice qué la dispara.");
        }
        if (codigoDeEnfermedad == null || codigoDeEnfermedad.isBlank()) {
            throw new DatoInvalido(
                    "La regla de declaración de %s no dice qué enfermedad se declara.".formatted(codigoDePrueba));
        }
        if (resultadoQueDeclara == null || resultadoQueDeclara.isBlank()) {
            throw new DatoInvalido(
                    ("La regla de declaración de %s no dice con qué resultado se declara. Sin criterio se "
                                    + "declararía también un negativo, y eso no es un exceso de celo: es meter "
                                    + "en el sistema de vigilancia casos que hay que retirar después.")
                            .formatted(codigoDePrueba));
        }
    }

    /** Si un valor cualitativo concreto es el que obliga a declarar. */
    public boolean laDispara(String codigoDelValor) {
        return resultadoQueDeclara.equals(codigoDelValor);
    }
}
