package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import java.util.UUID;

/**
 * «Cuando esta prueba salga alterada, añade esta otra, y cuéntalo así».
 *
 * <p>Sale del catálogo del laboratorio —las propiedades {@code prueba-refleja} y
 * {@code motivo-de-la-refleja} del concepto—, <strong>nunca de un {@code if} en el código</strong>.
 * Cambiar a qué prueba refleja la TSH tiene que ser cambiar un catálogo, no desplegar un backend.
 *
 * <p>El motivo viene <strong>ya redactado desde la terminología</strong> y no se compone aquí. Podría
 * componerse —«derivada de un %s alterado» con el nombre de la prueba dentro— y sería un error: el
 * español no lo permite sin conocer el género de cada nombre de prueba, y quien redacta la regla es
 * quien tiene que redactar cómo se le cuenta al paciente.
 *
 * @param codigoQueDispara la prueba que, alterada, provoca la otra
 * @param codigoReflejo la prueba que el laboratorio añade
 * @param motivo la frase que se publica en {@code triggeredBy.reason}
 */
public record ReglaRefleja(String codigoQueDispara, String codigoReflejo, String motivo) {

    public ReglaRefleja {
        if (codigoQueDispara == null || codigoQueDispara.isBlank()) {
            throw new DatoInvalido("Una regla refleja tiene que decir qué prueba la dispara.");
        }
        if (codigoReflejo == null || codigoReflejo.isBlank()) {
            throw new DatoInvalido("Una regla refleja tiene que decir qué prueba se añade.");
        }
        // Sin la frase, la determinación aparecería en el informe sin que nadie la hubiera pedido y
        // sin poder decir por qué. Es el mismo criterio que en `Disparo`, aplicado una capa antes:
        // una regla que no sabe explicarse no se llega a aplicar.
        if (motivo == null || motivo.isBlank()) {
            throw new DatoInvalido(
                    ("La regla refleja de «%s» no trae su motivo (`motivo-de-la-refleja`), así que la prueba "
                                    + "añadida no podría explicarse. Sin él no se aplica.")
                            .formatted(codigoQueDispara));
        }
        if (codigoQueDispara.equals(codigoReflejo)) {
            throw new DatoInvalido(
                    ("«%s» no puede reflejarse a sí misma: eso no es una refleja, es una repetición, y se "
                                    + "declara con `repeat`.")
                            .formatted(codigoQueDispara));
        }
    }

    /** El disparo que corresponde a esta regla, colgado del resultado que la activó. */
    public Disparo disparadoPor(UUID resultadoQueLaActivo) {
        return new Disparo(resultadoQueLaActivo, TipoDeDisparo.REFLEJA, motivo);
    }
}
