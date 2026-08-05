package es.hispalis.backend.dominio.informe;

/** Puerto de salida del agregado {@link Informe}. */
public interface RepositorioDeInformes {

    void guardar(Informe informe);
}
