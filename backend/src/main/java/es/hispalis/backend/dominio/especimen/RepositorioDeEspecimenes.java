package es.hispalis.backend.dominio.especimen;

import java.util.List;
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

    /**
     * Todo lo de un paciente. Lo pide el reconciliador (§15), que regenera la proyección desde el
     * dominio y necesita recorrerlo por persona: es lo que le permite ejecutarse sobre un subconjunto
     * en vez de sobre el laboratorio entero.
     */
    List<Especimen> buscarDePaciente(UUID pacienteId);
}
