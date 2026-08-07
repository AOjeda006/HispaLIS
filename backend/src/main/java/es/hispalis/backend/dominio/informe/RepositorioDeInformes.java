package es.hispalis.backend.dominio.informe;

import java.util.List;
import java.util.UUID;

/** Puerto de salida del agregado {@link Informe}. */
public interface RepositorioDeInformes {

    void guardar(Informe informe);

    /**
     * Todo lo de un paciente. Lo pide el reconciliador (§15), que regenera la proyección desde el
     * dominio y necesita recorrerlo por persona: es lo que le permite ejecutarse sobre un subconjunto
     * en vez de sobre el laboratorio entero.
     *
     * <p>Es la primera lectura que tiene este repositorio, y hasta ahora no la había porque nadie la
     * necesitaba: un informe se emite y se consulta por la API, que lee de la proyección. Añadirla
     * «por si acaso» habría sido código sin uso; añadirla ahora es responder a uno.
     */
    List<Informe> buscarDePaciente(UUID pacienteId);
}
