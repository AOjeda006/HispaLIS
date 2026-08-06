package es.hispalis.integracion.saliente;

import ca.uhn.hl7v2.model.v251.message.ORU_R01;
import es.hispalis.integracion.bus.BusDeHechos;
import es.hispalis.integracion.bus.HechoDelLaboratorio;
import es.hispalis.integracion.destino.ApiFhirDelLaboratorio;
import es.hispalis.integracion.mllp.EmisorMllp;
import es.hispalis.integracion.mllp.PropiedadesDelHis;
import java.util.List;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Manda al HIS el {@code ORU^R01} de cada informe que el laboratorio emite.
 *
 * <h2>El envío se dispara desde el hecho, no desde un {@code if}</h2>
 *
 * <p>Es el criterio del ítem 28 y no es una preferencia de estilo. Si el envío colgara de una línea
 * dentro de {@code EmitirInforme}, con el HIS caído habría dos salidas y las dos malas: o el informe
 * no se emite —el laboratorio deja de funcionar porque un tercero está caído—, o se emite y el envío
 * se pierde sin que quede constancia. Colgándolo del hecho del {@code outbox}, el informe se emite
 * siempre y el envío se reintenta cuando el HIS vuelva.
 *
 * <p>El hecho <strong>no trae los datos</strong>, solo la referencia: la carga del {@code outbox} no
 * lleva PHI (invariante 6). Así que este componente lee el informe por la API FHIR, que además tiene
 * la ventaja de leer el estado actual y no una foto que pudo quedarse vieja en la cola.
 *
 * <h2>Qué pasa cuando el HIS no contesta</h2>
 *
 * <p>El hecho queda anotado como {@code FALLIDO} con su motivo, y <strong>no se reintenta solo</strong>.
 * Un bucle de reintentos contra un HIS caído llena el log y no arregla nada; volver a intentarlo es una
 * decisión de operación —se borra la fila del desplazamiento— igual que reprocesar desde la DLQ.
 * Cuando llegue Kafka (ítem 30), esto pasa a ser el <em>commit</em> del <em>offset</em> y la política
 * de reintentos la pondrá el consumidor.
 */
@Component
public class NotificadorAlHis {

    private static final Logger LOG = LoggerFactory.getLogger(NotificadorAlHis.class);

    /** Cuántos hechos se miran por vuelta. */
    private static final int POR_VUELTA = 20;

    private final BusDeHechos bus;
    private final ApiFhirDelLaboratorio laboratorio;
    private final TransformadorInformeAOru transformador;
    private final EmisorMllp emisor;
    private final PropiedadesDelHis his;

    public NotificadorAlHis(
            BusDeHechos bus,
            ApiFhirDelLaboratorio laboratorio,
            TransformadorInformeAOru transformador,
            EmisorMllp emisor,
            PropiedadesDelHis his) {
        this.bus = bus;
        this.laboratorio = laboratorio;
        this.transformador = transformador;
        this.emisor = emisor;
        this.his = his;
    }

    /**
     * Una vuelta del sondeo.
     *
     * <p>Es {@code public} porque los tests la llaman directamente en vez de esperar al reloj: un test
     * que duerme para ver si el temporizador ya pasó es un test que falla de vez en cuando en la CI y
     * nadie sabe por qué.
     */
    @Scheduled(fixedDelayString = "${hispalis.his.sondeo-ms:5000}")
    public void unaVuelta() {
        for (HechoDelLaboratorio hecho : bus.sinConsumir(POR_VUELTA)) {
            atender(hecho);
        }
    }

    private void atender(HechoDelLaboratorio hecho) {
        if (!HechoDelLaboratorio.INFORME_EMITIDO.equals(hecho.tipo())) {
            // Los demás hechos son de otros consumidores —el notificador EDO del hito 3, el relay a
            // Kafka—. Se descartan explícitamente para que el desplazamiento avance: dejarlos
            // pendientes haría que la consulta arrastrase para siempre lo que este motor no mira.
            bus.anotarConsumo(hecho.id(), BusDeHechos.Consumo.DESCARTADO, "No es un informe emitido: " + hecho.tipo());
            return;
        }

        var referencia = hecho.referencia("diagnosticReportRef");
        if (referencia.isEmpty()) {
            bus.anotarConsumo(
                    hecho.id(),
                    BusDeHechos.Consumo.FALLIDO,
                    "El hecho INFORME_EMITIDO no trae `diagnosticReportRef` en su carga.");
            return;
        }

        try {
            DiagnosticReport informe = laboratorio.leerInforme(referencia.get());
            List<Observation> resultados = laboratorio.leerResultados(informe.getResult().stream()
                    .map(org.hl7.fhir.r5.model.Reference::getReference)
                    .toList());
            Patient paciente = laboratorio.leerPaciente(informe.getSubject().getReference());

            ORU_R01 mensaje = transformador.construir(
                    informe,
                    resultados,
                    paciente,
                    his.aplicacion(),
                    his.instalacion(),
                    his.aplicacionDestino(),
                    his.instalacionDestino(),
                    controlIdPara(hecho));

            String acuse = emisor.enviar(mensaje);
            if ("AA".equals(acuse)) {
                bus.anotarConsumo(hecho.id(), BusDeHechos.Consumo.ENTREGADO, referencia.get());
                LOG.info("ORU^R01 del informe {} entregado al HIS", referencia.get());
            } else {
                // Un `AE`/`AR` del HIS no es un fallo de red: es el HIS diciendo que hay algo mal en
                // el mensaje. Reintentarlo tal cual daría el mismo resultado.
                bus.anotarConsumo(hecho.id(), BusDeHechos.Consumo.FALLIDO, "El HIS respondió MSA-1=" + acuse);
                LOG.warn("El HIS rechazó el ORU^R01 del informe {} con {}", referencia.get(), acuse);
            }
        } catch (RuntimeException | ca.uhn.hl7v2.HL7Exception | java.io.IOException noSePudo) {
            bus.anotarConsumo(hecho.id(), BusDeHechos.Consumo.FALLIDO, noSePudo.getMessage());
            LOG.warn("No se pudo notificar el informe {} al HIS: {}", referencia.get(), noSePudo.getMessage());
        }
    }

    /**
     * {@code MSH-10} derivado del hecho.
     *
     * <p>Derivado y no aleatorio a propósito: si el mismo hecho se reintenta, el HIS recibe el mismo
     * identificador de control y <strong>su</strong> deduplicación lo reconoce. Es la misma regla que
     * este motor aplica en la entrada, aplicada en la salida.
     */
    private static String controlIdPara(HechoDelLaboratorio hecho) {
        return "LAB" + hecho.id().toString().replace("-", "").substring(0, 17);
    }
}
