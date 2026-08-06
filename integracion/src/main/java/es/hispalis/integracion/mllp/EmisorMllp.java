package es.hispalis.integracion.mllp;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.app.Initiator;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import es.hispalis.integracion.hl7.ContextosHl7;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * El lado emisor del motor: manda un mensaje al HIS por MLLP y lee su acuse.
 *
 * <p>El <em>framing</em> lo pone HAPI, igual que al recibir, y por la misma razón. Lo que sí es
 * decisión de este componente es <strong>qué se hace con el acuse</strong>: un {@code AA} es entrega
 * confirmada; cualquier otra cosa —{@code AE}, {@code AR}, o ninguna respuesta— es entrega
 * <strong>no</strong> confirmada, y el hecho que la disparó no se marca como entregado. Dar por bueno
 * un envío porque el {@code write} no lanzó excepción es cómo se pierden resultados sin que nadie se
 * entere.
 *
 * <p>La conexión se reutiliza mientras siga viva. Abrir una por mensaje funciona y satura la tabla de
 * conexiones del HIS en cuanto hay volumen; HAPI ya sabe reabrirla si se cayó.
 */
@Component
public class EmisorMllp {

    private static final Logger LOG = LoggerFactory.getLogger(EmisorMllp.class);

    private final PropiedadesDelHis destino;
    private final HapiContext contexto;

    private Connection conexion;

    public EmisorMllp(PropiedadesDelHis destino) {
        this.destino = destino;
        this.contexto = ContextosHl7.nuevo();
        if (destino.tls()) {
            contexto.setSocketFactory(new FabricaDeSocketsDeSalida(destino));
        }
    }

    /**
     * Manda el mensaje y espera el acuse.
     *
     * @return el {@code MSA-1} que devolvió el HIS
     * @throws NoSePudoEntregar si no hubo conexión, no hubo acuse o el acuse no fue legible
     */
    public String enviar(Message mensaje) {
        try {
            Initiator iniciador = conexionViva().getInitiator();
            Message acuse = iniciador.sendAndReceive(mensaje);
            String codigo = new Terser(acuse).get("MSA-1");
            if (codigo == null || codigo.isBlank()) {
                throw new NoSePudoEntregar("El HIS respondió sin MSA-1: no hay acuse que interpretar.", null);
            }
            return codigo.strip();
        } catch (HL7Exception | IOException | ca.uhn.hl7v2.llp.LLPException noLlego) {
            // Se cierra la conexión: si el fallo fue del socket, reutilizarla en el siguiente envío
            // daría un error distinto y más confuso que el original.
            cerrar();
            throw new NoSePudoEntregar(
                    "No se pudo entregar el mensaje al HIS en %s:%d".formatted(destino.servidor(), destino.puerto()),
                    noLlego);
        }
    }

    private synchronized Connection conexionViva() throws HL7Exception {
        if (conexion == null || !conexion.isOpen()) {
            conexion = contexto.newClient(destino.servidor(), destino.puerto(), destino.tls());
            LOG.info(
                    "Conexión MLLP hacia el HIS abierta en {}:{} ({})",
                    destino.servidor(),
                    destino.puerto(),
                    destino.tls() ? "TLS" : "sin cifrar");
        }
        return conexion;
    }

    @PreDestroy
    public synchronized void cerrar() {
        if (conexion != null) {
            try {
                conexion.close();
            } catch (RuntimeException | IOException noSeDejaCerrar) {
                // Cerrar un socket que ya está roto vuelve a fallar, y no hay nada que reparar: lo
                // que importa es soltar la referencia para que la próxima llamada abra una nueva.
                LOG.debug("La conexión con el HIS no se cerró limpiamente: {}", noSeDejaCerrar.getMessage());
            }
            conexion = null;
        }
    }

    /** El mensaje no llegó, o llegó y no se pudo confirmar. */
    public static class NoSePudoEntregar extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NoSePudoEntregar(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
