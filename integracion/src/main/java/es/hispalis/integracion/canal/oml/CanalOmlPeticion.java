package es.hispalis.integracion.canal.oml;

import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v251.message.OML_O21;
import ca.uhn.hl7v2.model.v251.segment.PID;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.canal.Canal;
import es.hispalis.integracion.canal.Desenlace;
import es.hispalis.integracion.canal.adt.TransformadorAdtAPaciente;
import es.hispalis.integracion.destino.ApiFhirDelLaboratorio;
import es.hispalis.integracion.hl7.CabeceraMsh;
import es.hispalis.integracion.hl7.Campos;
import es.hispalis.integracion.hl7.ContextosHl7;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Canal de peticiones: {@code OML^O21} → {@code ServiceRequest} + {@code Specimen}.
 *
 * <p>Es el canal en el que aterriza <strong>D22</strong>, y conviene tener presente qué se decidió y
 * qué se paga por ello. El par volante + muestra quiere escribirse junto, y la puerta transaccional
 * de FHIR está cerrada (ADR-0014) porque ese camino se salta el núcleo. De las tres salidas que se
 * plantearon se eligió la (b): <strong>el motor escribe recurso a recurso y la atomicidad la pone el
 * reproceso</strong>.
 *
 * <p>Lo que eso significa aquí, en concreto:
 *
 * <ul>
 *   <li><strong>Cada escritura va precedida de una búsqueda</strong> por la clave de negocio del
 *       recurso. Es lo que hace que reaplicar el mensaje entero sea inofensivo, y por tanto lo que
 *       hace que el reproceso valga como mecanismo de atomicidad.
 *   <li><strong>Las líneas van antes que las muestras.</strong> Un volante sin muestra es un estado
 *       transitorio comprensible —lo pedido está registrado y el tubo aún no ha llegado—; una muestra
 *       sin volante no se parece a nada del mundo real.
 *   <li><strong>Si falla a mitad, lo escrito se queda escrito</strong> y el mensaje va a la DLQ con lo
 *       que ya se había hecho anotado. La ventana de huérfano es un estado transitorio legítimo, no
 *       un agujero: está documentada y tiene dueño, que es el reproceso.
 * </ul>
 */
@Component
public class CanalOmlPeticion implements Canal {

    private static final Logger LOG = LoggerFactory.getLogger(CanalOmlPeticion.class);

    private static final String TIPO = "OML";
    private static final String EVENTO = "O21";

    /** El código de estructura de la tabla 0354 para este evento. Ver {@code adr-0018}. */
    private static final String ESTRUCTURA = "OML_O21";

    private final TransformadorOmlAPeticion transformador;
    private final TransformadorAdtAPaciente demografia;
    private final ApiFhirDelLaboratorio laboratorio;

    /**
     * Este canal parsea con su propio parser, <strong>no voraz</strong>.
     *
     * <p>No es una optimización ni una manía: con el parser por defecto, un volante de dos pruebas
     * llega aquí como un volante de una, sin error y sin aviso. La explicación completa está en
     * {@link ContextosHl7#noVoraz()}. El modo de parseo es una propiedad de la gramática del mensaje,
     * no del motor, y no hay un ajuste que valga a la vez para {@code OML^O21} y {@code ORU^R01}.
     */
    private final HapiContext contexto = ContextosHl7.noVoraz();

    public CanalOmlPeticion(
            TransformadorOmlAPeticion transformador,
            TransformadorAdtAPaciente demografia,
            ApiFhirDelLaboratorio laboratorio) {
        this.transformador = transformador;
        this.demografia = demografia;
        this.laboratorio = laboratorio;
    }

    @Override
    public String nombre() {
        return "oml-peticion";
    }

    @Override
    public boolean acepta(CabeceraMsh cabecera) {
        return TIPO.equals(cabecera.tipo()) && EVENTO.equals(cabecera.evento());
    }

    @Override
    public Indices indices(Message recibido) {
        if (!(recibido instanceof OML_O21 oml)) {
            return Indices.NINGUNO;
        }
        return new Indices(
                demografia.nhcDe(oml.getPATIENT().getPID()).orElse(null),
                Campos.opcional(oml.getPATIENT()
                                .getPATIENT_VISIT()
                                .getPV1()
                                .getVisitNumber()
                                .getIDNumber())
                        .orElse(null));
    }

    @Override
    public Desenlace procesar(MensajeEntrante mensaje, Message recibido) {
        Optional<String> estructuraRara = estructuraQueNoCuadra(mensaje.cabecera());
        if (estructuraRara.isPresent()) {
            return Desenlace.rechazado(estructuraRara.get());
        }
        if (!(recibido instanceof OML_O21)) {
            return Desenlace.rechazado("Se esperaba la estructura %s y llegó %s."
                    .formatted(ESTRUCTURA, recibido.getClass().getSimpleName()));
        }

        // Se vuelve a parsear el ORIGINAL con el parser no voraz de este canal. Son los mismos bytes
        // que llegaron por el hilo —los mismos que reprocesaría la DLQ—, así que el resultado es el
        // mismo aquí y en un reproceso de dentro de seis meses.
        OML_O21 oml;
        try {
            oml = (OML_O21) contexto.getPipeParser().parse(mensaje.crudo());
        } catch (ca.uhn.hl7v2.HL7Exception | ClassCastException noSeDejaLeer) {
            return Desenlace.rechazado("No se pudo leer el OML^O21: " + noSeDejaLeer.getMessage());
        }

        try {
            PID pid = oml.getPATIENT().getPID();
            String nhc = demografia
                    .nhcDe(pid)
                    .orElseThrow(() -> new TransformadorOmlAPeticion.PeticionIncompleta(
                            "El OML^O21 no trae número de historia clínica en PID-3 con tipo «MR»."));

            // El paciente tiene que existir. Un OML no da de alta: el circuito del HIS manda antes el
            // ADT de la admisión, y crear aquí al paciente con la demografía de un volante metería
            // filiaciones a medias por la puerta de atrás.
            String pacienteRef = laboratorio
                    .buscarPacientePorNhc(nhc)
                    .orElseThrow(() -> new TransformadorOmlAPeticion.PeticionIncompleta(
                            // Sin acentos circunflejos en el texto: `^` es el separador de componente
                            // de v2, y HAPI lo escapa como `\S\` al meterlo en el ERR del acuse. El
                            // operador del HIS leería «mande primero el ADT\S\A01», que no ayuda.
                            ("El paciente con NHC %s no está registrado en el laboratorio. Un OML O21 no da de alta: "
                                            + "mande primero el ADT A01 de la admisión.")
                                    .formatted(nhc)));

            List<TransformadorOmlAPeticion.LineaPedida> lineas = transformador.lineas(oml);
            List<TransformadorOmlAPeticion.MuestraAnunciada> muestras = transformador.muestras(oml);

            List<String> producidas = new ArrayList<>();
            for (TransformadorOmlAPeticion.LineaPedida linea : lineas) {
                producidas.add(escribirLinea(linea, pacienteRef));
            }
            for (TransformadorOmlAPeticion.MuestraAnunciada muestra : muestras) {
                producidas.add(escribirMuestra(muestra, pacienteRef));
            }

            LOG.info(
                    "Canal {}: volante con {} línea(s) y {} muestra(s) aplicado (control {})",
                    nombre(),
                    lineas.size(),
                    muestras.size(),
                    mensaje.cabecera().controlId());
            return Desenlace.aceptado(String.join(" ", producidas));

        } catch (TransformadorOmlAPeticion.PeticionIncompleta faltaAlgo) {
            return Desenlace.errorDeAplicacion(faltaAlgo.getMessage());
        } catch (ApiFhirDelLaboratorio.ElLaboratorioRechaza rechazo) {
            return Desenlace.errorDeAplicacion(rechazo.getMessage());
        }
    }

    /** Idempotente: si la línea ya está en el volante, se reutiliza en vez de duplicarla. */
    private String escribirLinea(TransformadorOmlAPeticion.LineaPedida linea, String pacienteRef) {
        return laboratorio
                .buscarLinea(linea.numeroDeVolante(), linea.codigoDePrueba())
                .orElseGet(() -> laboratorio.registrarLinea(transformador.aServiceRequest(linea, pacienteRef)));
    }

    /** Idempotente: la clave es el número de acceso, que es el que va impreso en la etiqueta. */
    private String escribirMuestra(TransformadorOmlAPeticion.MuestraAnunciada muestra, String pacienteRef) {
        return laboratorio
                .buscarEspecimen(muestra.numeroDeAcceso())
                .orElseGet(() -> laboratorio.registrarEspecimen(transformador.aSpecimen(muestra, pacienteRef)));
    }

    private static Optional<String> estructuraQueNoCuadra(CabeceraMsh cabecera) {
        return cabecera.estructuraDeclarada()
                .filter(declarada -> !ESTRUCTURA.equals(declarada))
                .map(declarada -> "MSH-9-3 declara la estructura «%s»; para OML^O21 la tabla 0354 de V2.5.1 dice %s."
                        .formatted(declarada, ESTRUCTURA));
    }
}
