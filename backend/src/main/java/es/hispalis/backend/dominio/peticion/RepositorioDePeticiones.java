package es.hispalis.backend.dominio.peticion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida del agregado {@link Peticion}. */
public interface RepositorioDePeticiones {

    void guardar(Peticion peticion);

    Optional<Peticion> buscarPorId(UUID id);

    /**
     * Devuelve <strong>todas</strong> las líneas de los volantes indicados, para un paciente.
     *
     * <p>El paciente no sobra: {@code numero_de_peticion} no es único —y no debe serlo, es lo que
     * agrupa las líneas de un mismo volante—, y hoy lo genera el cliente. Buscar solo por número
     * arrastraría al alcance de un informe las líneas de otra persona que hubiera coincidido de
     * número, y el informe se bloquearía esperando un trabajo que no es suyo.
     *
     * @return las líneas, ordenadas por volante y código de prueba, para que los mensajes de error
     *     no cambien de orden entre ejecuciones
     */
    List<Peticion> buscarLineasDeVolantes(Collection<String> numerosDePeticion, UUID pacienteId);
}
