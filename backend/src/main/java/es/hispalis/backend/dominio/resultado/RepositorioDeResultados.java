package es.hispalis.backend.dominio.resultado;

import java.util.Optional;
import java.util.UUID;

/** Puerto de salida del agregado {@link Resultado}. */
public interface RepositorioDeResultados {

    /** Guarda un resultado recién informado. */
    void guardar(Resultado resultado);

    /** Recupera un resultado por su identidad. */
    Optional<Resultado> buscarPorId(UUID id);
}
