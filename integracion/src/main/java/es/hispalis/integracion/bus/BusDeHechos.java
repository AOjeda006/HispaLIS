package es.hispalis.integracion.bus;

import java.util.List;
import java.util.UUID;

/**
 * Los hechos que el laboratorio cuenta al exterior, vistos desde el motor.
 *
 * <p><strong>Esto es el bus, hoy.</strong> §11 dice que el laboratorio publicará sus hechos en Kafka
 * y que de ellos colgarán el {@code ORU^R01} saliente y el notificador EDO; el relay que los publica
 * es el ítem 30 y todavía no existe. Mientras tanto, el motor lee la <strong>tabla del
 * {@code outbox}</strong>, que es exactamente lo que ese relay leerá.
 *
 * <p>Que sea un puerto no es ceremonia: el día del ítem 30, la implementación pasa a ser un
 * consumidor de Kafka y <strong>el notificador no cambia</strong>. Lo que se está probando hoy —que
 * el envío se dispara desde el hecho y no desde un {@code if} dentro de un caso de uso— es lo que
 * seguirá valiendo entonces.
 *
 * <h2>Dos cosas que este puerto NO hace, a propósito</h2>
 *
 * <ul>
 *   <li><strong>No lee {@code dominio.*}.</strong> El {@code outbox} es la bandeja de salida, no el
 *       modelo del laboratorio. Un {@code SELECT} contra {@code dominio} desde aquí rompería D5, y el
 *       comentario de la migración {@code V1} del motor lo dice con esas palabras.
 *   <li><strong>No sella {@code outbox.hecho.publicado_en}.</strong> Esa columna es del relay a Kafka.
 *       Dos consumidores marcando la misma casilla hacen que el primero deje al otro sin su hecho, así
 *       que el motor lleva su propio desplazamiento en su propio esquema — que es lo que hace
 *       cualquier grupo de consumidores.
 * </ul>
 */
public interface BusDeHechos {

    /**
     * Los hechos que este motor todavía no ha consumido, del más antiguo al más reciente.
     *
     * <p>El orden importa y es el de creación: una validación no puede consumirse antes que el
     * resultado que valida.
     */
    List<HechoDelLaboratorio> sinConsumir(int limite);

    /** Deja constancia de qué hizo el motor con el hecho. */
    void anotarConsumo(UUID hechoId, Consumo consumo, String detalle);

    /** Qué se hizo con un hecho. */
    enum Consumo {

        /** Se construyó el mensaje y el HIS lo acusó. */
        ENTREGADO,

        /** No interesa a este motor —de momento, todo lo que no sea un informe emitido—. */
        DESCARTADO,

        /**
         * Se intentó y no se pudo.
         *
         * <p>Queda anotado para no reintentarlo en bucle contra un HIS caído. Volver a intentarlo es
         * una decisión de operación: se borra la fila del desplazamiento y el hecho vuelve a estar
         * pendiente.
         */
        FALLIDO
    }
}
