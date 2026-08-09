package es.hispalis.backend.dominio.peticion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida del agregado {@link Peticion}. */
public interface RepositorioDePeticiones {

    void guardar(Peticion peticion);

    /** Sobrescribe una línea ya registrada. Hoy el único cambio que existe es la anulación. */
    void actualizar(Peticion peticion);

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

    /**
     * Si ese volante ya tiene pedida esa prueba, para esa persona.
     *
     * <p>Existe por la <strong>prueba refleja</strong>: informar dos veces la misma TSH alterada no
     * puede añadir dos T4 libres. Se pregunta por volante y no por resultado porque lo que no debe
     * duplicarse es la <em>petición</em> — y da igual si la primera la pidió el clínico o la añadió
     * el laboratorio.
     *
     * <p>Devuelve un booleano y no la línea a propósito: quien pregunta esto no va a hacer nada con
     * la línea existente, solo decidir si añade otra.
     */
    boolean yaPedidaEnElVolante(String numeroDePeticion, UUID pacienteId, String codigoDePrueba);

    /**
     * Todo lo de un paciente. Lo pide el reconciliador (§15), que regenera la proyección desde el
     * dominio y necesita recorrerlo por persona: es lo que le permite ejecutarse sobre un subconjunto
     * en vez de sobre el laboratorio entero.
     */
    List<Peticion> buscarDePaciente(UUID pacienteId);
}
