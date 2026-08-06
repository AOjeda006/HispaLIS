package es.hispalis.integracion.mllp;

import ca.uhn.hl7v2.protocol.ReceivingApplicationExceptionHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La última red: si algo revienta por debajo del canal, el emisor recibe un acuse igualmente.
 *
 * <p>La regla del motor es que <strong>siempre se responde</strong>. Un emisor v2 al que se le deja
 * la conexión colgando sin acuse hace una de dos cosas, y las dos son peores que un rechazo: o
 * reintenta indefinidamente el mismo mensaje, o lo da por entregado y nunca más se sabe de él.
 *
 * <p>Aquí no se reescribe el acuse que HAPI ya haya compuesto: se deja pasar y se registra el fallo
 * con el mensaje entrante, que es lo que permite reconstruir después qué llegó. El log
 * <strong>no incluye el contenido</strong> del mensaje, que es PHI; solo su identificador de control.
 */
public class AcusePorFalloInterno implements ReceivingApplicationExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AcusePorFalloInterno.class);

    @Override
    public String processException(String entrante, Map<String, Object> metadatos, String saliente, Exception fallo) {
        LOG.error("Fallo por debajo del canal atendiendo un mensaje MLLP; se responde con el acuse de HAPI", fallo);
        return saliente;
    }
}
