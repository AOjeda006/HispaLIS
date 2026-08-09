package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import java.util.UUID;

/**
 * De dónde viene una determinación que no se pidió por volante.
 *
 * <p><strong>El motivo es obligatorio, y esa es la mitad que se olvida.</strong> Sin él, quien mira
 * la historia ve dos potasios del mismo día que se contradicen —6,9 y 4,3— y un enlace entre ellos
 * que no explica cuál vale. El elemento {@code triggeredBy.reason} es {@code 0..1} en FHIR; aquí es
 * {@code 1..1} porque un laboratorio que añade una prueba por su cuenta tiene que poder decir por
 * qué, y porque es lo que la web y la app enseñan al usuario — en palabras, no con un icono.
 *
 * <p>Es la misma disciplina que {@link UmbralCritico} con su procedencia: el dato que hace falta para
 * poder auditar la decisión se exige en el constructor, no se recomienda en la documentación.
 *
 * @param origen el resultado que lo provocó
 * @param tipo qué clase de disparo es
 * @param motivo la frase que lo explica, en español y ya redactada
 */
public record Disparo(UUID origen, TipoDeDisparo tipo, String motivo) {

    public Disparo {
        if (origen == null) {
            throw new DatoInvalido("Un disparo sin resultado de origen no enlaza con nada.");
        }
        if (tipo == null) {
            throw new DatoInvalido("Hay que decir de qué clase es el disparo: refleja, repetición o re-ejecución.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new DatoInvalido(
                    ("Un disparo sin motivo deja dos cifras de la misma prueba enlazadas y sin explicar cuál "
                            + "vale. Di por qué existe esta determinación."));
        }
        motivo = motivo.strip();
    }

    /** La referencia FHIR al resultado que lo provocó. */
    public String referenciaDelOrigen() {
        return "Observation/" + origen;
    }
}
