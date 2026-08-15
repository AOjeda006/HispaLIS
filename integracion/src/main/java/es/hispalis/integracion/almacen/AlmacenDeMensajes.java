package es.hispalis.integracion.almacen;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * La bitácora del motor: todo lo que entra queda escrito <strong>antes</strong> de tocarlo.
 *
 * <p>Es lo que se pierde al no usar Mirth (D11) y hay que construir. Cumple dos funciones a la vez, y
 * no por casualidad: el registro del original y la <strong>deduplicación</strong> son la misma
 * operación —el {@code INSERT} con clave única—, así que no existe la ventana en la que un mensaje
 * se procesa antes de quedar apuntado.
 *
 * <p>Deliberadamente <strong>fuera de la transacción</strong> de proceso: si la escritura contra la
 * API FHIR falla, el mensaje tiene que seguir en el almacén con su motivo, no desaparecer con el
 * {@code rollback}. Cada operación de este puerto confirma por su cuenta.
 */
public interface AlmacenDeMensajes {

    /**
     * Registra el mensaje si no se había visto, y dice qué hacer con él.
     *
     * <p>La clave es {@code MSH-3 + MSH-4 + MSH-10}, y <strong>no {@code MSH-10} a secas</strong>: esa
     * es la trampa. El estándar solo obliga a que el identificador de control sea único <em>por
     * emisor</em>, así que dos analizadores que reinician su contador coinciden a la primera, y
     * deduplicar solo por él descartaría mensajes buenos <strong>en silencio</strong> —bastante peor
     * que procesarlos dos veces—. La impone el {@code UNIQUE} de la tabla, no un {@code SELECT}
     * previo: con la comprobación aparte hay una ventana en la que caben dos.
     * {@code PropiedadDeLaClaveDeDeduplicacionTest} recorre las ocho combinaciones de qué cambia.
     *
     * @return {@link Admision#NUEVO} si es la primera vez; {@link Admision#YA_PROCESADO} si ese mismo
     *     emisor ya mandó ese identificador de control y salió bien; {@link Admision#REINTENTO} si se
     *     vio antes pero no llegó a procesarse
     */
    Admision registrarSiEsNuevo(MensajeEntrante mensaje);

    /** Deja constancia de que el canal lo aplicó, y contra qué recurso. */
    void marcarProcesado(UUID id, String referenciaProducida);

    /** Deja constancia de que no se aplicó, y por qué. El original se conserva intacto. */
    void marcarRechazado(UUID id, String motivo);

    /**
     * La bandeja de errores: lo que entró y no se pudo aplicar, lo más reciente primero.
     *
     * <p>No es una tabla aparte, y no lo es a propósito. Un mensaje en la DLQ es una fila de este
     * mismo archivo en estado {@code RECHAZADO}: moverlo a otra tabla obligaría a devolverlo para
     * reprocesarlo, y ese viaje de ida y vuelta es una transacción más que puede quedarse a medias
     * — justo la avería que la DLQ existe para evitar.
     */
    List<MensajeArchivado> bandejaDeErrores(int limite);

    /** Un mensaje concreto del archivo, con su original. */
    Optional<MensajeArchivado> buscar(UUID id);

    /**
     * Apunta que se va a intentar aplicar, otra vez.
     *
     * <p>Se incrementa <strong>antes</strong> de intentarlo, no después de que salga bien: un
     * reproceso que revienta a mitad tiene que dejar rastro de que se intentó, o la bandeja miente
     * sobre cuántas veces se ha probado ya.
     */
    void anotarIntento(UUID id);

    /** Qué hacer con un mensaje según lo que el almacén ya sepa de él. */
    enum Admision {

        /** No se había visto. Se procesa. */
        NUEVO,

        /**
         * Mismo emisor, mismo {@code MSH-10}, y la vez anterior salió bien.
         *
         * <p>Se acusa recibo con {@code AA} y <strong>no se vuelve a escribir</strong>. Responder un
         * error sería peor: el emisor reintentaría eternamente un mensaje que ya se aplicó.
         */
        YA_PROCESADO,

        /**
         * Se vio antes pero quedó sin aplicar.
         *
         * <p>Se procesa otra vez, sobre la misma fila. Un mensaje que falló y se reenvía tiene que
         * poder entrar: si la deduplicación lo bloqueara, el único camino de recuperación sería
         * tocar la base de datos a mano.
         */
        REINTENTO
    }
}
