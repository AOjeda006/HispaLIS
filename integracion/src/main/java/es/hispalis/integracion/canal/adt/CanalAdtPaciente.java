package es.hispalis.integracion.canal.adt;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v251.message.ADT_A01;
import ca.uhn.hl7v2.model.v251.segment.PID;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.canal.Canal;
import es.hispalis.integracion.canal.Desenlace;
import es.hispalis.integracion.destino.ApiFhirDelLaboratorio;
import es.hispalis.integracion.hl7.CabeceraMsh;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.r5.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Canal de demografía: {@code ADT^A01} y {@code ADT^A08} → {@code Patient}.
 *
 * <p>Es el primer canal del motor a propósito. Es el único contrato de la tabla de canales —la de
 * la memoria técnica, §6.7— que produce <strong>un solo recurso</strong>, así que estrena la
 * tubería entera —listener, almacén, deduplicación, acuses— sin arrastrar además el problema de
 * atomicidad que D22 resuelve para el {@code OML^O21}.
 *
 * <h2>Los dos eventos comparten estructura, y eso hay que saberlo antes de escribir el mapeo</h2>
 *
 * <p>{@code A08} <strong>no tiene estructura propia</strong>: la tabla 0354 de V2.5.1 dice que
 * {@code ADT_A01} cubre {@code A01}, {@code A04}, {@code A08} y {@code A13}. No existe ningún
 * {@code ADT_A08}, ni en V2.5 ni en V2.5.1, ni en el capítulo 2 ni en el apéndice A — está cruzado y
 * medido en {@code docs/adr/adr-0018-la-tabla-0354-se-contradice-consigo-misma.md}. Un emisor que
 * mande {@code MSH-9-3 = ADT_A08} está mandando un código que no existe, y aquí se rechaza con
 * {@code AR} diciendo cuál es el bueno, en vez de dejar que reviente el parser con un mensaje que no
 * apunta a su causa.
 *
 * <h2>Qué hace cada evento</h2>
 *
 * <ul>
 *   <li><strong>{@code A01} (admisión):</strong> da de alta al paciente. Si el NHC ya está
 *       registrado, <strong>corrige su filiación</strong> en vez de fallar: un reingreso es un
 *       {@code A01} legítimo, y además es lo que hace que reprocesar el mismo mensaje no produzca dos
 *       pacientes.
 *   <li><strong>{@code A08} (corrección):</strong> corrige la filiación. Si el paciente
 *       <strong>no</strong> existe, <strong>no lo crea</strong>: se rechaza con {@code AE}. Crear a
 *       partir de una corrección daría de alta a un paciente por la puerta de atrás, sin el
 *       {@code A01} que documenta por qué está en el laboratorio.
 * </ul>
 */
@Component
public class CanalAdtPaciente implements Canal {

    private static final Logger LOG = LoggerFactory.getLogger(CanalAdtPaciente.class);

    private static final String TIPO = "ADT";
    private static final Set<String> EVENTOS = Set.of("A01", "A08");

    /** El único código de estructura válido para estos eventos. Ver la nota de la clase. */
    private static final String ESTRUCTURA = "ADT_A01";

    private final TransformadorAdtAPaciente transformador;
    private final ApiFhirDelLaboratorio laboratorio;

    public CanalAdtPaciente(TransformadorAdtAPaciente transformador, ApiFhirDelLaboratorio laboratorio) {
        this.transformador = transformador;
        this.laboratorio = laboratorio;
    }

    @Override
    public String nombre() {
        return "adt-paciente";
    }

    @Override
    public boolean acepta(CabeceraMsh cabecera) {
        return TIPO.equals(cabecera.tipo()) && EVENTOS.contains(cabecera.evento());
    }

    @Override
    public Indices indices(Message recibido) {
        if (!(recibido instanceof ADT_A01 adt)) {
            return Indices.NINGUNO;
        }
        return new Indices(
                transformador.nhcDe(adt.getPID()).orElse(null),
                numeroDeEpisodio(adt).orElse(null));
    }

    @Override
    public Desenlace procesar(MensajeEntrante mensaje, Message recibido) {
        CabeceraMsh cabecera = mensaje.cabecera();

        Optional<String> estructuraRara = estructuraQueNoExiste(cabecera);
        if (estructuraRara.isPresent()) {
            return Desenlace.rechazado(estructuraRara.get());
        }
        if (!(recibido instanceof ADT_A01 adt)) {
            return Desenlace.rechazado(("Se esperaba la estructura %s y llegó %s. Los eventos A01 y A08 comparten "
                            + "estructura en V2.5.1.")
                    .formatted(ESTRUCTURA, recibido.getClass().getSimpleName()));
        }

        try {
            PID pid = adt.getPID();
            Patient paciente = transformador.aPatient(pid);
            String nhc = mensaje.nhc();
            Optional<String> yaRegistrado = laboratorio.buscarPacientePorNhc(nhc);

            if ("A08".equals(cabecera.evento()) && yaRegistrado.isEmpty()) {
                return Desenlace.errorDeAplicacion(
                        ("El paciente con NHC %s no está registrado en el laboratorio, así que no hay filiación que "
                                        + "corregir. Un A08 no da de alta: mande primero el A01 de la admisión.")
                                .formatted(nhc));
            }

            String referencia = yaRegistrado
                    .map(existente -> laboratorio.corregirPaciente(existente, paciente))
                    .orElseGet(() -> laboratorio.darDeAltaPaciente(paciente));
            LOG.info(
                    "Canal {}: {} aplicado sobre {} (control {})",
                    nombre(),
                    cabecera.tipoYEvento(),
                    referencia,
                    cabecera.controlId());
            return Desenlace.aceptado(referencia);

        } catch (TransformadorAdtAPaciente.DemografiaIncompleta faltaAlgo) {
            return Desenlace.errorDeAplicacion(faltaAlgo.getMessage());
        } catch (ApiFhirDelLaboratorio.ElLaboratorioRechaza rechazo) {
            // El laboratorio contestó y dijo que no. Es `AE` y no `AR`: hay algo concreto que
            // corregir, y quien lo corrige es una persona con el `OperationOutcome` delante.
            return Desenlace.errorDeAplicacion(rechazo.getMessage());
        } catch (HL7Exception noSeDejaRecorrer) {
            return Desenlace.rechazado("No se pudo leer el PID del mensaje: " + noSeDejaRecorrer.getMessage());
        }
    }

    /**
     * Comprueba {@code MSH-9-3} contra la tabla 0354.
     *
     * <p>Se mira solo cuando viene: es opcional, y la mayoría de los emisores no lo mandan. Cuando
     * viene y está mal, el mensaje se rechaza aquí con una explicación en vez de dejar que el parser
     * falle más abajo con un error que no dice cuál es el código correcto.
     */
    private static Optional<String> estructuraQueNoExiste(CabeceraMsh cabecera) {
        return cabecera.estructuraDeclarada()
                .filter(declarada -> !ESTRUCTURA.equals(declarada))
                .map(declarada -> ("MSH-9-3 declara la estructura «%s», que no existe en la tabla 0354 de V2.5.1. "
                                + "Los eventos A01, A04, A08 y A13 comparten la estructura %s: no hay una por evento.")
                        .formatted(declarada, ESTRUCTURA));
    }

    private static Optional<String> numeroDeEpisodio(ADT_A01 adt) {
        String episodio = adt.getPV1().getVisitNumber().getIDNumber().getValue();
        return episodio == null || episodio.isBlank() ? Optional.empty() : Optional.of(episodio.strip());
    }
}
