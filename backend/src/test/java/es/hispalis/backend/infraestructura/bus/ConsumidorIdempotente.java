package es.hispalis.backend.infraestructura.bus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.avro.generic.GenericRecord;

/**
 * Un consumidor del bus escrito como hay que escribirlos: <strong>idempotente</strong>.
 *
 * <p>La entrega es al menos una vez y no hay forma barata de que no lo sea, así que un consumidor que
 * suponga «cada mensaje llega exactamente una vez» está mal desde el primer día — solo que el fallo
 * no aparece hasta que el relay se reinicia entre publicar y marcar. La defensa es una línea: llevar
 * cuenta de los {@code hechoId} ya aplicados y no volver a aplicarlos.
 *
 * <p>Lleva un estado que <strong>se rompería visiblemente</strong> con un duplicado —una cuenta por
 * paciente y el orden en que llegó lo suyo—, porque un consumidor que solo guardara el último valor
 * pasaría el test aunque no dedujera nada. Es el mismo criterio que el reproceso del motor.
 *
 * <p>En un consumidor de verdad, el conjunto de aplicados no es un {@code Set} en memoria: es una
 * tabla con el {@code hechoId} como clave primaria, y el {@code INSERT} <em>es</em> la comprobación.
 * Aquí sobra porque es un test, y está dicho para que a nadie se le copie tal cual.
 */
final class ConsumidorIdempotente {

    private final Set<String> aplicados = new HashSet<>();
    private final Map<String, Integer> hechosPorPaciente = new HashMap<>();
    private final Map<String, List<String>> ordenPorPaciente = new LinkedHashMap<>();

    /** @return {@code true} si el hecho era nuevo; {@code false} si ya se había aplicado */
    boolean aplicar(GenericRecord hecho) {
        String hechoId = hecho.get("hechoId").toString();
        String pacienteId = hecho.get("pacienteId").toString();

        if (!aplicados.add(hechoId)) {
            return false;
        }
        hechosPorPaciente.merge(pacienteId, 1, Integer::sum);
        ordenPorPaciente
                .computeIfAbsent(pacienteId, quien -> new java.util.ArrayList<>())
                .add(hecho.get("tipo").toString());
        return true;
    }

    int hechosDe(String pacienteId) {
        return hechosPorPaciente.getOrDefault(pacienteId, 0);
    }

    List<String> ordenDe(String pacienteId) {
        return ordenPorPaciente.getOrDefault(pacienteId, List.of());
    }
}
