package es.hispalis.integracion.canal;

import ca.uhn.hl7v2.model.Message;
import es.hispalis.integracion.almacen.AlmacenDeMensajes;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.hl7.CabeceraMsh;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Elige el canal, lo ejecuta y deja el resultado apuntado en el archivo.
 *
 * <p>Existe porque hay <strong>dos</strong> caminos por los que un mensaje se aplica —la entrega por
 * MLLP y el reproceso desde la DLQ— y los dos tienen que hacer exactamente lo mismo. Si el reproceso
 * tuviera su propia copia del enrutado, el día que se añada un canal habría que acordarse de los dos
 * sitios; y peor: un mensaje podría reprocesarse por un canal distinto del que lo rechazó, que es la
 * clase de diferencia que nadie encuentra mirando el código.
 *
 * <p>Lo que <strong>no</strong> hace es deduplicar. Eso es del camino de entrada: un reproceso es una
 * orden explícita de volver a aplicar, y bloquearlo por deduplicación dejaría la DLQ sin salida.
 */
@Component
public class Despachador {

    private static final Logger LOG = LoggerFactory.getLogger(Despachador.class);

    private final List<Canal> canales;
    private final AlmacenDeMensajes almacen;

    public Despachador(List<Canal> canales, AlmacenDeMensajes almacen) {
        this.canales = canales;
        this.almacen = almacen;
    }

    /** El canal que atiende ese tipo de mensaje, si hay alguno. */
    public Optional<Canal> canalPara(CabeceraMsh cabecera) {
        return canales.stream().filter(canal -> canal.acepta(cabecera)).findFirst();
    }

    /**
     * Aplica el mensaje y deja constancia de cómo fue.
     *
     * @param mensaje el original, ya registrado en el archivo
     * @param recibido el mismo mensaje, parseado
     */
    public Desenlace aplicar(MensajeEntrante mensaje, Message recibido) {
        Optional<Canal> canal = canalPara(mensaje.cabecera());
        if (canal.isEmpty()) {
            Desenlace sinCanal = Desenlace.rechazado("No hay ningún canal para %s en este laboratorio."
                    .formatted(mensaje.cabecera().tipoYEvento()));
            almacen.marcarRechazado(mensaje.id(), sinCanal.detalleTecnico());
            return sinCanal;
        }

        // Al archivo va el detalle TÉCNICO y al emisor el otro. Ver `Desenlace`: son dos
        // destinatarios distintos y compartir texto sacaba la sentencia SQL por el puerto MLLP.
        Desenlace desenlace = conCuidado(canal.get(), mensaje, recibido);
        if (desenlace.seAplico()) {
            almacen.marcarProcesado(mensaje.id(), desenlace.detalleTecnico());
        } else {
            almacen.marcarRechazado(mensaje.id(), desenlace.detalleTecnico());
        }
        return desenlace;
    }

    /**
     * Un fallo inesperado de un canal no puede tumbar la conexión ni dejar al emisor sin respuesta.
     *
     * <p>Se traduce a {@code AE} —error de aplicación, mírelo una persona— y queda en el archivo con
     * su motivo, que es lo que lo pone en la DLQ y lo hace reprocesable.
     *
     * <p>El mensaje del fallo va al log y al archivo; <strong>no sale por el cable</strong> y
     * <strong>el contenido del mensaje v2 tampoco va al log</strong>. Lo primero porque el mensaje de
     * una excepción inesperada puede ser cualquier cosa —el fuzzing encontró una que traía la
     * sentencia SQL del archivo entera— y lo segundo porque un mensaje v2 volcado en un log es un
     * volcado clínico completo.
     */
    private static Desenlace conCuidado(Canal canal, MensajeEntrante mensaje, Message recibido) {
        try {
            return canal.procesar(mensaje, recibido);
        } catch (RuntimeException inesperado) {
            LOG.error(
                    "Canal {}: fallo inesperado con el control {}",
                    canal.nombre(),
                    mensaje.cabecera().controlId(),
                    inesperado);
            return Desenlace.falloInterno(
                    "El canal %s no pudo aplicar el mensaje: %s".formatted(canal.nombre(), inesperado.getMessage()));
        }
    }
}
