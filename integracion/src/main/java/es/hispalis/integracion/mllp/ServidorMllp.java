package es.hispalis.integracion.mllp;

import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.llp.ExtendedMinLowerLayerProtocol;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import es.hispalis.integracion.hl7.ContextosHl7;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * El listener MLLP: el <em>origen</em> de todos los canales.
 *
 * <p>Es uno solo para todo el motor. Un canal no abre puertos —enruta— y tener un listener por canal
 * obligaría al HIS a conocer un puerto por tipo de mensaje, que es exactamente la clase de
 * acoplamiento que un motor de integración existe para quitar.
 *
 * <h2>El <em>framing</em> no se escribe a mano</h2>
 *
 * <p>Los bytes de sobre —{@code 0x0B} al principio, {@code 0x1C 0x0D} al final— los pone y los quita
 * HAPI. Y conviene saber por qué importa: el documento normativo de MLLP es un estándar de
 * <strong>HL7 V3</strong>, el apéndice B de V2.5.1 está vacío, y ese documento está
 * <strong>retirado desde mayo de 2025 sin sustituto designado</strong>. Lo que falta es fuente
 * citable, no código: implementarlo a mano significaría copiar de memoria un formato que ya no se
 * puede citar.
 *
 * <h2>Por qué el LLP «extendido»</h2>
 *
 * <p>{@link ExtendedMinLowerLayerProtocol} lee {@code MSH-18} <strong>antes</strong> de convertir los
 * bytes a texto, y codifica la respuesta con el {@code MSH-18} del acuse. Con el LLP mínimo, un
 * mensaje en {@code 8859/1} se leería con el juego por defecto de la JVM y {@code MUÑOZ} llegaría
 * como {@code MU?OZ} <strong>sin una sola excepción</strong>.
 */
@Component
public class ServidorMllp implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ServidorMllp.class);

    private final PropiedadesMllp propiedades;
    private final ReceivingApplication<ca.uhn.hl7v2.model.Message> receptor;
    private final HapiContext contexto;

    private HL7Service servicio;

    public ServidorMllp(PropiedadesMllp propiedades, ReceptorDeMensajes receptor) {
        this.propiedades = propiedades;
        this.receptor = receptor;
        this.contexto = contextoPara(propiedades);
    }

    private static HapiContext contextoPara(PropiedadesMllp propiedades) {
        // Modelo canónico de V2.5.1, LLP extendido y sin validación de HAPI: ver `ContextosHl7`, que
        // es donde vive esa configuración para que el listener, el emisor y el reproceso la
        // compartan. Lo único propio del listener es su fábrica de sockets.
        HapiContext contexto = ContextosHl7.nuevo();
        if (propiedades.tls().habilitado()) {
            contexto.setSocketFactory(new FabricaDeSocketsTls(propiedades.tls()));
        }
        return contexto;
    }

    @Override
    public void start() {
        if (!propiedades.tls().habilitado()) {
            LOG.warn(
                    "El listener MLLP arranca SIN TLS en el puerto {}. Solo para pruebas locales: el plano de "
                            + "sistemas lleva nombre, fecha de nacimiento y DNI del paciente en texto plano.",
                    propiedades.puerto());
        }
        servicio = contexto.newServer(propiedades.puerto(), propiedades.tls().habilitado());
        servicio.registerApplication(receptor);
        servicio.setExceptionHandler(new AcusePorFalloInterno());
        try {
            servicio.startAndWait();
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió el arranque del listener MLLP", interrumpido);
        }
        LOG.info(
                "Listener MLLP escuchando en el puerto {} ({})",
                propiedades.puerto(),
                propiedades.tls().habilitado() ? "TLS" : "sin cifrar");
    }

    @Override
    @PreDestroy
    public void stop() {
        if (servicio != null) {
            servicio.stopAndWait();
            servicio = null;
        }
    }

    @Override
    public boolean isRunning() {
        return servicio != null && servicio.isRunning();
    }

    /** El puerto efectivo. Útil en pruebas, donde se pide uno libre al sistema. */
    public int puerto() {
        return propiedades.puerto();
    }
}
