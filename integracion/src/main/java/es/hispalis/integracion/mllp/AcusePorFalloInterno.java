package es.hispalis.integracion.mllp;

import ca.uhn.hl7v2.model.v251.message.ACK;
import ca.uhn.hl7v2.protocol.ReceivingApplicationExceptionHandler;
import es.hispalis.integracion.hl7.ContextosHl7;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La última red: si algo revienta por debajo del canal, el emisor recibe un acuse igualmente.
 *
 * <p>La regla del motor es que <strong>siempre se responde</strong>. Un emisor v2 al que se le deja
 * la conexión colgando sin acuse hace una de dos cosas, y las dos son peores que un rechazo: o
 * reintenta indefinidamente el mismo mensaje, o lo da por entregado y nunca más se sabe de él.
 *
 * <h2>El agujero que tenía esta clase, y que el fuzzing destapó</h2>
 *
 * <p>Hasta el 2026-08-15 esta clase se limitaba a devolver el acuse que HAPI hubiera compuesto. Y
 * HAPI <strong>no siempre compone uno</strong>: para construirlo necesita leer el {@code MSH} del
 * mensaje entrante —de ahí salen el emisor, el destinatario y el identificador de control del acuse—,
 * así que cuando lo que llega no tiene {@code MSH} legible, {@code getCriticalResponseData} falla,
 * HAPI registra «Exception occurred while logging parse failure», llama aquí con {@code saliente} a
 * <strong>nulo</strong> y, al recibir nulo de vuelta, lanza <em>«Application exception handler may
 * not return null»</em>. El emisor no recibe nada. Justo lo que la regla existe para evitar, y
 * justamente en el caso peor: un {@code MSH} truncado, unos delimitadores redefinidos a medias o un
 * mensaje cortado por una conexión que se cayó. Cuarenta y cinco de ciento cinco entradas hostiles
 * generadas se quedaban sin respuesta.
 *
 * <p>Ahora, cuando HAPI no trae acuse, se compone uno de último recurso: {@code AR} —el problema es
 * del mensaje y reintentarlo no lo arregla— con el código 100 de la tabla 0357.
 *
 * <h2>Por qué el acuse de último recurso va sin {@code MSA-2}</h2>
 *
 * <p>{@code MSA-2} tiene que llevar el {@code MSH-10} del mensaje que se acusa, y aquí <strong>no se
 * sabe cuál es</strong>: si el mensaje se pudiera leer, HAPI habría compuesto el acuse él. Rescatarlo
 * a ojo —contar separadores hasta el décimo campo de la primera línea— devolvería el contenido de
 * <em>algún</em> campo del mensaje roto, que en un mensaje con los campos corridos es tan
 * probablemente la fecha de nacimiento del paciente como el identificador de control. Se prefiere un
 * {@code MSA-2} vacío a un {@code MSA-2} inventado: por una conexión MLLP hay un mensaje en vuelo
 * cada vez, así que el emisor sabe igualmente cuál le han rechazado.
 *
 * <p>Del acuse que sí compone HAPI no se toca nada: se deja pasar y se registra el fallo con el
 * identificador de control, que es lo que permite reconstruir después qué llegó. El log
 * <strong>no incluye el contenido</strong> del mensaje, que es PHI.
 */
public class AcusePorFalloInterno implements ReceivingApplicationExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AcusePorFalloInterno.class);

    private static final DateTimeFormatter SELLO_V2 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId ZONA = ZoneId.of("Europe/Madrid");

    /**
     * {@code ERR-3} de la tabla 0357: error de secuencia de segmentos.
     *
     * <p>Es el código honesto para «no he sabido leer esto». {@code 207} —error interno de la
     * aplicación— diría que el problema es nuestro y llevaría al operador del HIS a abrir una
     * incidencia contra el laboratorio en vez de mirar lo que su sistema está emitiendo.
     */
    private static final String CODIGO_DE_ERROR = "100";

    private static final String MOTIVO =
            "El mensaje no se ha podido leer: la cabecera MSH no es interpretable. No se ha procesado y "
                    + "reenviarlo tal cual no va a servir.";

    /**
     * Lo que se devuelve si ni siquiera se puede componer el acuse con el modelo.
     *
     * <p>Un literal en una clase que precisamente existe para que nunca falte respuesta. El
     * {@code MSH-10} es fijo porque en este camino no hay nada de lo que derivarlo, y un acuse con un
     * identificador repetido sigue siendo infinitamente mejor que ningún acuse.
     */
    private static final String ULTIMISIMO_RECURSO =
            "MSH|^~\\&|%s|%s|||%s||ACK|NAK000000000000000000|P|2.5.1" + "||||||8859/1\rMSA|AR||" + MOTIVO + "\r";

    private final String aplicacion;
    private final String instalacion;

    /**
     * @param aplicacion {@code MSH-3} con el que este laboratorio se presenta
     * @param instalacion {@code MSH-4}
     */
    public AcusePorFalloInterno(String aplicacion, String instalacion) {
        this.aplicacion = aplicacion;
        this.instalacion = instalacion;
    }

    @Override
    public String processException(String entrante, Map<String, Object> metadatos, String saliente, Exception fallo) {
        if (saliente != null && !saliente.isBlank()) {
            LOG.error("Fallo por debajo del canal atendiendo un mensaje MLLP; se responde con el acuse de HAPI", fallo);
            return saliente;
        }
        LOG.error(
                "El mensaje recibido no se deja leer y HAPI no ha podido componer acuse; se responde AR de último "
                        + "recurso ({} bytes recibidos)",
                entrante == null ? 0 : entrante.length(),
                fallo);
        return rechazoDeUltimoRecurso();
    }

    /** Un {@code ACK} con {@code MSA-1 = AR}, compuesto con el modelo y no concatenando barras. */
    private String rechazoDeUltimoRecurso() {
        try {
            ACK acuse = new ACK();
            acuse.initQuickstart("ACK", "", "P");

            var msh = acuse.getMSH();
            msh.getSendingApplication().getNamespaceID().setValue(aplicacion);
            msh.getSendingFacility().getNamespaceID().setValue(instalacion);
            msh.getDateTimeOfMessage()
                    .getTime()
                    .setValue(SELLO_V2.format(Instant.now().atZone(ZONA)));
            msh.getMessageControlID()
                    .setValue("NAK"
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
            msh.getVersionID().getVersionID().setValue(ContextosHl7.VERSION);
            msh.getCountryCode().setValue("ES");
            // Se declara Y se usa: el escritor MLLP mira este campo del mensaje saliente para elegir
            // con qué juego codifica los bytes. El texto de este acuse es ASCII puro a propósito.
            msh.getCharacterSet(0).setValue("8859/1");

            acuse.getMSA().getAcknowledgmentCode().setValue("AR");
            acuse.getERR().getHL7ErrorCode().getIdentifier().setValue(CODIGO_DE_ERROR);
            acuse.getERR().getSeverity().setValue("E");
            acuse.getERR().getUserMessage().setValue(MOTIVO);

            return ContextosHl7.nuevo().getPipeParser().encode(acuse);
        } catch (Exception niAsi) {
            LOG.error("Tampoco se ha podido componer el acuse de último recurso; se manda el literal", niAsi);
            return ULTIMISIMO_RECURSO.formatted(
                    aplicacion, instalacion, SELLO_V2.format(Instant.now().atZone(ZONA)));
        }
    }
}
