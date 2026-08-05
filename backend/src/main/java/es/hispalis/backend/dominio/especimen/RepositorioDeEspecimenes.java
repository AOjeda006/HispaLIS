package es.hispalis.backend.dominio.especimen;

import java.util.Optional;
import java.util.UUID;

/** Puerto de salida del agregado {@link Especimen}. */
public interface RepositorioDeEspecimenes {

    /**
     * Guarda una muestra recién recibida.
     *
     * @throws es.hispalis.backend.dominio.ConflictoDeNegocio si su número de acceso ya está emitido
     */
    void guardar(Especimen especimen);

    /** Recupera una muestra por su identidad. */
    Optional<Especimen> buscarPorId(UUID id);
}
