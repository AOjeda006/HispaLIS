package es.hispalis.backend.dominio.peticion;

import java.util.Optional;
import java.util.UUID;

/** Puerto de salida del agregado {@link Peticion}. */
public interface RepositorioDePeticiones {

    void guardar(Peticion peticion);

    Optional<Peticion> buscarPorId(UUID id);
}
