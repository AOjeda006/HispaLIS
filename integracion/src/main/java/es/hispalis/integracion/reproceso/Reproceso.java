package es.hispalis.integracion.reproceso;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import es.hispalis.integracion.almacen.AlmacenDeMensajes;
import es.hispalis.integracion.almacen.MensajeArchivado;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.canal.Canal;
import es.hispalis.integracion.canal.Desenlace;
import es.hispalis.integracion.canal.Despachador;
import es.hispalis.integracion.hl7.CabeceraMsh;
import es.hispalis.integracion.hl7.ContextosHl7;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Vuelve a aplicar un mensaje del archivo, entero.
 *
 * <p><strong>Esto es lo que sostiene D22.</strong> El motor escribe recurso a recurso porque la
 * puerta transaccional de FHIR está cerrada, y la atomicidad la pone este componente: si un
 * {@code OML^O21} se quedó con el volante escrito y la muestra sin escribir, reprocesarlo completa el
 * estado. La ventana de huérfano deja de ser un agujero y pasa a ser un estado transitorio con dueño.
 *
 * <h2>La idempotencia no vive aquí</h2>
 *
 * <p>Y es importante saberlo, porque es lo que la hace fiable. Este servicio no lleva ninguna cuenta
 * de lo que ya escribió: <strong>vuelve a ejecutar el canal desde el mensaje original</strong>, y son
 * los canales los que preguntan al laboratorio por la clave de negocio del recurso <em>antes de
 * decidir qué escribir</em>. Un registro propio de «lo que ya hice» tendría que mantenerse
 * sincronizado con el laboratorio, y el día que se desviase —una escritura que sí llegó pero cuya
 * respuesta se perdió— duplicaría exactamente cuando más falta hacía que no lo hiciera.
 *
 * <p>Consecuencia práctica: reprocesar {@code n} veces un mensaje ya aplicado deja el mismo estado que
 * aplicarlo una. {@code PropiedadDeLaIdempotenciaTest} lo comprueba para los tres canales y para
 * {@code n} de uno a cinco.
 *
 * <h2>Lo idempotente es el estado, no la cuenta de escrituras</h2>
 *
 * <p>Los dos canales clínicos, al encontrar el recurso, <strong>no escriben</strong>. El de
 * demografía sí: un {@code A08} es una corrección, y corregir la filiación que ya está corregida es
 * un {@code PUT} sobre el mismo recurso con el mismo contenido. El estado final es idéntico —que es
 * lo que la palabra significa— pero hay una escritura más por reproceso. Se deja así a propósito:
 * comparar antes la filiación entrante con la guardada para ahorrarse el {@code PUT} metería en el
 * motor una noción de «igual» que es del laboratorio, y equivocarla se paga en corrección perdida.
 */
@Service
public class Reproceso {

    private static final Logger LOG = LoggerFactory.getLogger(Reproceso.class);

    private final AlmacenDeMensajes almacen;
    private final Despachador despachador;
    private final HapiContext contexto = ContextosHl7.nuevo();

    public Reproceso(AlmacenDeMensajes almacen, Despachador despachador) {
        this.almacen = almacen;
        this.despachador = despachador;
    }

    /**
     * Reaplica el mensaje identificado.
     *
     * @param id el identificador del archivo, no el {@code MSH-10}
     * @return cómo fue esta vez
     * @throws MensajeDesconocido si no hay ningún mensaje con ese identificador
     */
    public Resultado reaplicar(UUID id) {
        MensajeArchivado archivado = almacen.buscar(id)
                .orElseThrow(() -> new MensajeDesconocido(
                        "No hay ningún mensaje archivado con el " + "identificador %s.".formatted(id)));

        Message recibido;
        CabeceraMsh cabecera;
        try {
            recibido = contexto.getPipeParser().parse(archivado.crudo());
            cabecera = CabeceraMsh.de(recibido);
        } catch (HL7Exception noSeDejaParsear) {
            // Un mensaje que no se puede parsear no se puede reprocesar, y no hay reintento que lo
            // arregle. Se deja dicho en el archivo y ahí se queda hasta que alguien lo mire.
            String motivo = "El mensaje archivado no se puede volver a parsear: " + noSeDejaParsear.getMessage();
            almacen.marcarRechazado(id, motivo);
            return new Resultado(id, false, motivo);
        }

        Optional<Canal> canal = despachador.canalPara(cabecera);
        if (canal.isEmpty()) {
            String motivo = "No hay ningún canal para %s en este laboratorio.".formatted(cabecera.tipoYEvento());
            almacen.marcarRechazado(id, motivo);
            return new Resultado(id, false, motivo);
        }

        // Se conserva el identificador del archivo: el reproceso reescribe LA MISMA fila. Una fila
        // nueva por intento haría que la bandeja creciese con copias del mismo mensaje y que el
        // contador de intentos no significara nada.
        Canal.Indices indices = canal.get().indices(recibido);
        MensajeEntrante mensaje = new MensajeEntrante(
                archivado.id(),
                cabecera,
                indices.nhc(),
                indices.episodio(),
                archivado.crudo(),
                archivado.recibidoEn() == null ? Instant.now() : archivado.recibidoEn());

        almacen.anotarIntento(id);
        Desenlace desenlace = despachador.aplicar(mensaje, recibido);

        LOG.info(
                "Reproceso del control {} ({}): {}",
                cabecera.controlId(),
                cabecera.tipoYEvento(),
                desenlace.resultado());
        // El detalle técnico y no el del cable: esto lo lee la consola del motor, que es interna.
        return new Resultado(id, desenlace.seAplico(), desenlace.detalleTecnico());
    }

    /**
     * Cómo fue el reproceso.
     *
     * @param id el mensaje reprocesado
     * @param aplicado si esta vez sí se aplicó
     * @param detalle las referencias producidas, o el motivo por el que sigue sin aplicarse
     */
    public record Resultado(UUID id, boolean aplicado, String detalle) {}

    /** No hay tal mensaje en el archivo. */
    public static class MensajeDesconocido extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MensajeDesconocido(String mensaje) {
            super(mensaje);
        }
    }
}
