package es.hispalis.backend.fhir;

import es.hispalis.backend.dominio.DatoInvalido;
import java.util.UUID;
import org.hl7.fhir.r5.model.Reference;

/**
 * Resuelve una referencia FHIR a la identidad del agregado al que apunta.
 *
 * <p>Es trivial <strong>porque se decidió que lo fuera</strong>: el id lógico de cada recurso es el
 * mismo UUID que identifica al agregado en el dominio. Sin esa decisión haría falta una tabla de
 * correspondencias entre los dos mundos, consultarla en cada escritura, y mantenerla coherente.
 */
public final class Referencias {

    private Referencias() {
        // Utilidad.
    }

    /**
     * Extrae la identidad del agregado de una referencia como {@code Specimen/<uuid>}.
     *
     * @param referencia la referencia tal y como llegó en el recurso
     * @param queEsperaba nombre legible de lo referido, para el mensaje de error
     * @return el identificador del agregado
     * @throws DatoInvalido si la referencia falta o no apunta a un identificador de este sistema
     */
    public static UUID identidadDe(Reference referencia, String queEsperaba) {
        if (referencia == null || referencia.getReferenceElement().isEmpty()) {
            throw new DatoInvalido("Falta la referencia %s, que es obligatoria.".formatted(queEsperaba));
        }
        String idLogico = referencia.getReferenceElement().getIdPart();
        try {
            return UUID.fromString(idLogico);
        } catch (IllegalArgumentException e) {
            // Pasa cuando se referencia un recurso que no creó este sistema —o uno inventado—. El
            // mensaje dice qué se recibió, porque si no el fallo es indistinguible de un 404.
            throw new DatoInvalido("La referencia %s «%s» no corresponde a ningún %s de este laboratorio."
                    .formatted(queEsperaba, idLogico, queEsperaba));
        }
    }
}
