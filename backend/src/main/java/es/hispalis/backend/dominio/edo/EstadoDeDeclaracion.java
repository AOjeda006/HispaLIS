package es.hispalis.backend.dominio.edo;

/**
 * En qué punto va una declaración frente a Salud Pública.
 *
 * <p>⚠️ <strong>No es el estado del {@code Task}.</strong> Aquel dice cómo va la tarea dentro del
 * laboratorio; esto dice cómo va la obligación legal. Una tarea «en curso» puede ser una declaración
 * enviada y sin acusar, que es un estado del que hay que responder y que no existe en ningún
 * vocabulario de FHIR.
 *
 * <p>El vocabulario lo publica la guía en {@code CodeSystem/estados-declaracion-edo}, y los nombres de
 * aquí son los mismos códigos a propósito: son los estados de la máquina del laboratorio, no una
 * terminología clínica que pueda crecer por su cuenta — por eso sí son un {@code enum} y no una
 * consulta al servidor de terminología. La regla del invariante 4 es sobre códigos <em>clínicos</em>.
 */
public enum EstadoDeDeclaracion {

    /**
     * Registrada y todavía sin salir.
     *
     * <p>Es también donde se queda una declaración cuyo envío falló: si el destinatario no la tiene,
     * el laboratorio no ha declarado nada, y decirlo de otra forma sería fingir un avance.
     */
    PENDIENTE,

    /** Salió y llegó, pero sin número de registro. <strong>No cuenta como declarada.</strong> */
    ENVIADA,

    /** Hay acuse. Es el único estado en el que la obligación está cumplida. */
    ACUSADA,

    /** Salud Pública ha contestado que no la admite. Es una respuesta, no un fallo del canal. */
    RECHAZADA;

    /** Si desde aquí ya no se mueve sola. Un rechazo tampoco se reintenta: se resuelve a mano. */
    public boolean esFinal() {
        return this == ACUSADA || this == RECHAZADA;
    }
}
