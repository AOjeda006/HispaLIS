package es.hispalis.integracion.bus;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Un hecho publicado por el laboratorio.
 *
 * <p>La carga son <strong>referencias, nunca PHI</strong> — invariante 6, y el laboratorio lo
 * garantiza en la fábrica de su agregado {@code Hecho}, no con una comprobación de test. Consecuencia
 * directa para este motor: <strong>el hecho no trae los datos, así que hay que ir a buscarlos</strong>
 * por la API FHIR. No es un rodeo: es lo que mantiene el bus limpio y lo que hace que el consumidor
 * lea siempre el estado actual en vez de una foto que pudo quedarse vieja en la cola.
 *
 * @param id identidad del hecho
 * @param tipo el nombre del {@code TipoDeHecho} del laboratorio ({@code INFORME_EMITIDO}…)
 * @param pacienteId la clave de partición
 * @param carga referencias, por nombre de campo
 * @param creadoEn cuándo se apuntó
 */
public record HechoDelLaboratorio(UUID id, String tipo, UUID pacienteId, Map<String, String> carga, Instant creadoEn) {

    /** El nombre del tipo de hecho del que cuelga el {@code ORU^R01} saliente (§11, ítem 28). */
    public static final String INFORME_EMITIDO = "INFORME_EMITIDO";

    /** Una referencia concreta de la carga. */
    public Optional<String> referencia(String campo) {
        return Optional.ofNullable(carga.get(campo));
    }
}
