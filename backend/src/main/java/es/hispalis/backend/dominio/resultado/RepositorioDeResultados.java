package es.hispalis.backend.dominio.resultado;

/** Puerto de salida del agregado {@link Resultado}. */
public interface RepositorioDeResultados {

    /** Guarda un resultado recién informado. */
    void guardar(Resultado resultado);
}
