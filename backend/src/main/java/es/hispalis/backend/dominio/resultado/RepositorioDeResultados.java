package es.hispalis.backend.dominio.resultado;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Puerto de salida del agregado {@link Resultado}. */
public interface RepositorioDeResultados {

    /** Guarda un resultado recién informado. */
    void guardar(Resultado resultado);

    /** Recupera un resultado por su identidad. */
    Optional<Resultado> buscarPorId(UUID id);

    /**
     * De las líneas indicadas, cuáles tienen ya al menos un resultado.
     *
     * <p>Se pregunta por el conjunto entero y no línea a línea: son las líneas de un volante, se
     * necesitan todas a la vez y una consulta por cada una convertiría un informe de veinte
     * determinaciones en veinte viajes a la base de datos.
     */
    Set<UUID> lineasConResultado(Collection<UUID> lineasDePeticion);
}
