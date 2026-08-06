package es.hispalis.integracion.canal.oru;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.canal.Canal;
import es.hispalis.integracion.canal.Desenlace;
import es.hispalis.integracion.canal.adt.TransformadorAdtAPaciente;
import es.hispalis.integracion.destino.ApiFhirDelLaboratorio;
import es.hispalis.integracion.hl7.CabeceraMsh;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Canal de resultados entrantes: {@code ORU^R01} del analizador → {@code Observation}.
 *
 * <p>Lo que este canal <strong>no</strong> hace es tan importante como lo que hace: no valida. Un
 * resultado que entra por aquí queda <strong>preliminar</strong>, y sigue preliminar hasta que un
 * facultativo lo firma con {@code $validar} (ítem 18). El informe no lo recoge mientras tanto. Es la
 * diferencia entre «la máquina ha medido» y «alguien responde de esta cifra», y publicarla como final
 * sería firmar por el analizador.
 *
 * <h2>La muestra manda, y su invariante también</h2>
 *
 * <p>El resultado se ancla a la muestra por su número de acceso, que es lo que va impreso en la
 * etiqueta del tubo y lo que el analizador lee. Si esa muestra fue <strong>rechazada</strong>, el
 * núcleo del laboratorio rechaza el resultado (invariante C6) — y lo rechaza igual venga de la web o
 * de este canal, porque el motor entra por la misma puerta que todos (D5). Aquí eso se ve como un
 * {@code AE} con el motivo que dio el laboratorio, no como una comprobación repetida.
 */
@Component
public class CanalOruResultado implements Canal {

    private static final Logger LOG = LoggerFactory.getLogger(CanalOruResultado.class);

    private static final String TIPO = "ORU";
    private static final String EVENTO = "R01";

    /** El código de estructura de la tabla 0354 para este evento. Ver {@code adr-0018}. */
    private static final String ESTRUCTURA = "ORU_R01";

    private final TransformadorOruAResultado transformador;
    private final TransformadorAdtAPaciente demografia;
    private final ApiFhirDelLaboratorio laboratorio;

    public CanalOruResultado(
            TransformadorOruAResultado transformador,
            TransformadorAdtAPaciente demografia,
            ApiFhirDelLaboratorio laboratorio) {
        this.transformador = transformador;
        this.demografia = demografia;
        this.laboratorio = laboratorio;
    }

    @Override
    public String nombre() {
        return "oru-resultado";
    }

    @Override
    public boolean acepta(CabeceraMsh cabecera) {
        return TIPO.equals(cabecera.tipo()) && EVENTO.equals(cabecera.evento());
    }

    @Override
    public Indices indices(Message recibido) {
        if (!(recibido instanceof ORU_R01 oru) || oru.getPATIENT_RESULTReps() == 0) {
            return Indices.NINGUNO;
        }
        return new Indices(
                demografia.nhcDe(oru.getPATIENT_RESULT(0).getPATIENT().getPID()).orElse(null), null);
    }

    @Override
    public Desenlace procesar(MensajeEntrante mensaje, Message recibido) {
        Optional<String> estructuraRara = estructuraQueNoCuadra(mensaje.cabecera());
        if (estructuraRara.isPresent()) {
            return Desenlace.rechazado(estructuraRara.get());
        }
        if (!(recibido instanceof ORU_R01 oru)) {
            return Desenlace.rechazado("Se esperaba la estructura %s y llegó %s."
                    .formatted(ESTRUCTURA, recibido.getClass().getSimpleName()));
        }

        try {
            String nhc = Optional.ofNullable(mensaje.nhc())
                    .orElseThrow(() -> new TransformadorOruAResultado.ResultadoInaceptable(
                            "El ORU^R01 no trae número de historia clínica en PID-3 con tipo «MR»."));
            String pacienteRef = laboratorio
                    .buscarPacientePorNhc(nhc)
                    .orElseThrow(() -> new TransformadorOruAResultado.ResultadoInaceptable(
                            "El paciente con NHC %s no está registrado en el laboratorio.".formatted(nhc)));

            String acceso = transformador
                    .numeroDeAcceso(oru)
                    .orElseThrow(() -> new TransformadorOruAResultado.ResultadoInaceptable(
                            ("El ORU^R01 no dice sobre qué muestra se midió (ni SPM-2 ni OBR-3). Sin número de acceso "
                                    + "el resultado no es trazable hasta su tubo.")));
            String especimenRef = laboratorio
                    .buscarEspecimen(acceso)
                    .orElseThrow(() -> new TransformadorOruAResultado.ResultadoInaceptable(
                            ("La muestra con número de acceso %s no está registrada en el laboratorio. El resultado "
                                            + "llega antes que su muestra: reenvíe el OML^O21 o registre la muestra.")
                                    .formatted(acceso)));

            Optional<String> volante = transformador.numeroDeVolante(oru);

            List<String> producidos = new ArrayList<>();
            for (TransformadorOruAResultado.ResultadoMedido medido : transformador.resultados(oru)) {
                producidos.add(escribir(medido, pacienteRef, especimenRef, volante));
            }

            LOG.info(
                    "Canal {}: {} resultado(s) informados sobre la muestra {} (control {})",
                    nombre(),
                    producidos.size(),
                    acceso,
                    mensaje.cabecera().controlId());
            return Desenlace.aceptado(String.join(" ", producidos));

        } catch (TransformadorOruAResultado.ResultadoInaceptable noSePuede) {
            return Desenlace.errorDeAplicacion(noSePuede.getMessage());
        } catch (ApiFhirDelLaboratorio.ElLaboratorioRechaza rechazo) {
            return Desenlace.errorDeAplicacion(rechazo.getMessage());
        }
    }

    /**
     * Idempotente: la clave de un resultado es la pareja muestra + prueba.
     *
     * <p>La línea del volante se busca <strong>si el analizador devuelve el número</strong>, y si no
     * la hay el resultado se informa igual, sin {@code basedOn}. Un resultado sin volante es legítimo
     * —una urgencia, un control de calidad— y el agregado {@code Resultado} lo admite; lo que no sería
     * legítimo es inventar la línea para que el recurso quede más bonito.
     */
    private String escribir(
            TransformadorOruAResultado.ResultadoMedido medido,
            String pacienteRef,
            String especimenRef,
            Optional<String> volante) {
        return laboratorio
                .buscarResultado(especimenRef, medido.codigoDePrueba())
                .orElseGet(() -> {
                    String lineaRef = volante.flatMap(
                                    numero -> laboratorio.buscarLinea(numero, medido.codigoDePrueba()))
                            .orElse(null);
                    return laboratorio.informarResultado(
                            transformador.aObservation(medido, pacienteRef, especimenRef, lineaRef));
                });
    }

    private static Optional<String> estructuraQueNoCuadra(CabeceraMsh cabecera) {
        return cabecera.estructuraDeclarada()
                .filter(declarada -> !ESTRUCTURA.equals(declarada))
                .map(declarada -> "MSH-9-3 declara la estructura «%s»; para ORU^R01 la tabla 0354 de V2.5.1 dice %s."
                        .formatted(declarada, ESTRUCTURA));
    }
}
