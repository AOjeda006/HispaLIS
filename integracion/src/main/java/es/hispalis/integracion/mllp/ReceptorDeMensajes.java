package es.hispalis.integracion.mllp;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.ErrorCode;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.protocol.MetadataKeys;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import ca.uhn.hl7v2.util.Terser;
import es.hispalis.integracion.almacen.AlmacenDeMensajes;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.canal.Canal;
import es.hispalis.integracion.canal.Desenlace;
import es.hispalis.integracion.canal.Despachador;
import es.hispalis.integracion.hl7.CabeceraMsh;
import es.hispalis.integracion.hl7.CharsetDeclarado;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * La puerta de entrada del motor: recibe, archiva, deduplica, enruta y acusa.
 *
 * <p>Las <strong>tres garantías</strong> del motor viven aquí y no en cada canal, porque valen para
 * todos y porque dejarlas en el canal sería confiar en que el próximo que se escriba se acuerde de
 * las tres:
 *
 * <ol>
 *   <li><strong>El original se guarda íntegro antes de tocarlo.</strong> Lo que se archiva es lo que
 *       llegó por el hilo, tomado de {@link MetadataKeys#IN_RAW_MESSAGE}, no una reserialización de
 *       lo parseado: reserializar normaliza separadores y componentes vacíos, y entonces el archivo
 *       deja de responder a «¿qué mandó exactamente el emisor?».
 *   <li><strong>La deduplicación ocurre antes de escribir</strong>, y la hace el {@code INSERT} con
 *       clave única del almacén. Ver {@link AlmacenDeMensajes}.
 *   <li><strong>El charset se comprueba contra {@code MSH-18}.</strong> Si declara algo ilegible, lo
 *       que HAPI ya ha decodificado es basura y el mensaje se rechaza.
 * </ol>
 *
 * <p>Y una regla que no se negocia: <strong>siempre se responde</strong>. Un emisor v2 sin acuse o
 * reintenta indefinidamente o lo da por entregado, y las dos cosas son peores que un rechazo.
 */
@Component
public class ReceptorDeMensajes implements ReceivingApplication<Message> {

    private static final Logger LOG = LoggerFactory.getLogger(ReceptorDeMensajes.class);

    /** La versión que fija D12. Nada más entra: V2.5 y V2.5.1 no son intercambiables. */
    private static final String VERSION_ACEPTADA = "2.5.1";

    private final Despachador despachador;
    private final AlmacenDeMensajes almacen;

    public ReceptorDeMensajes(Despachador despachador, AlmacenDeMensajes almacen) {
        this.despachador = despachador;
        this.almacen = almacen;
    }

    /**
     * Acepta cualquier mensaje para poder <strong>contestar</strong> a todos.
     *
     * <p>Devolver {@code false} aquí no es «no es mío»: HAPI responde entonces con un error genérico
     * suyo y el mensaje no queda archivado. Es preferible aceptarlo, archivarlo y rechazarlo con un
     * {@code AR} que diga qué pasa.
     */
    @Override
    public boolean canProcess(Message recibido) {
        return true;
    }

    @Override
    public Message processMessage(Message recibido, java.util.Map<String, Object> metadatos)
            throws ReceivingApplicationException, HL7Exception {
        CabeceraMsh cabecera;
        try {
            cabecera = CabeceraMsh.de(recibido);
        } catch (CharsetDeclarado.CharsetNoSoportado ilegible) {
            // Sin cabecera fiable no hay clave de deduplicación ni metadatos, así que este es el
            // único camino en el que el mensaje no llega al archivo. Queda anotado en `docs/PLAN.md`.
            LOG.warn("Mensaje rechazado por charset: {}", ilegible.getMessage());
            return acusar(recibido, Desenlace.rechazado(ilegible.getMessage()));
        }

        Desenlace desenlace = atender(cabecera, recibido, crudoDe(metadatos, recibido));
        return acusar(recibido, desenlace);
    }

    private Desenlace atender(CabeceraMsh cabecera, Message recibido, String crudo) {
        try {
            cabecera.charset().exigirQueLoLeidoCuadre(crudo);
        } catch (CharsetDeclarado.CharsetNoCuadra mentira) {
            LOG.warn("Mensaje rechazado por incoherencia de charset: {}", mentira.getMessage());
            return Desenlace.rechazado(mentira.getMessage());
        }

        if (!VERSION_ACEPTADA.equals(cabecera.version())) {
            return Desenlace.rechazado(("Este motor habla HL7 V%s y el mensaje declara V%s en MSH-12. Las dos "
                            + "versiones no son intercambiables.")
                    .formatted(VERSION_ACEPTADA, cabecera.version()));
        }

        Optional<Canal> canal = despachador.canalPara(cabecera);
        if (canal.isEmpty()) {
            return Desenlace.rechazado(
                    "No hay ningún canal para %s en este laboratorio.".formatted(cabecera.tipoYEvento()));
        }

        Canal.Indices indices = canal.get().indices(recibido);
        MensajeEntrante mensaje = MensajeEntrante.recienLlegado(cabecera, indices.nhc(), indices.episodio(), crudo);

        AlmacenDeMensajes.Admision admision;
        try {
            admision = almacen.registrarSiEsNuevo(mensaje);
            if (admision != AlmacenDeMensajes.Admision.YA_PROCESADO) {
                almacen.anotarIntento(mensaje.id());
            }
        } catch (RuntimeException noSePudoArchivar) {
            // El archivo es la única pieza que queda FUERA de la red del despachador, porque ocurre
            // antes de que haya nada que despachar. Sin este `catch`, un fallo suyo se escapaba hasta
            // HAPI, que compone el acuse metiendo dentro el mensaje de la excepción: el fuzzing lo
            // encontró con un byte nulo en el texto —PostgreSQL rechaza el `INSERT`— y el HIS recibía
            // la sentencia SQL del laboratorio por el puerto MLLP.
            LOG.error(
                    "No se pudo archivar el mensaje con control {}; no se aplica",
                    cabecera.controlId(),
                    noSePudoArchivar);
            return Desenlace.falloInterno("No se pudo archivar el mensaje: " + noSePudoArchivar.getMessage());
        }

        if (admision == AlmacenDeMensajes.Admision.YA_PROCESADO) {
            LOG.info(
                    "Canal {}: {} con control {} ya se había aplicado; no se escribe otra vez",
                    canal.get().nombre(),
                    cabecera.tipoYEvento(),
                    cabecera.controlId());
            return Desenlace.duplicado("Ya aplicado en una entrega anterior de este mismo mensaje.");
        }

        return despachador.aplicar(mensaje, recibido);
    }

    /**
     * Construye el acuse.
     *
     * <p>El {@code MSH-18} del acuse se pone igual que el del mensaje recibido, y no es cosmética: el
     * escritor MLLP de HAPI mira {@code MSH-18} <em>del mensaje saliente</em> para decidir con qué
     * juego codifica los bytes. Sin esta línea, un acuse con acentos volvería corrupto al emisor.
     */
    private static Message acusar(Message recibido, Desenlace desenlace) throws HL7Exception {
        try {
            Message acuse = desenlace.seAplico() || desenlace.resultado() == Desenlace.Resultado.DUPLICADO
                    ? recibido.generateACK()
                    : recibido.generateACK(
                            AcknowledgmentCode.valueOf(desenlace.codigoDeAcuse()),
                            new HL7Exception(desenlace.detalle(), ErrorCode.APPLICATION_INTERNAL_ERROR));

            Terser deLlegada = new Terser(recibido);
            Terser deSalida = new Terser(acuse);
            deSalida.set("MSH-18", deLlegada.get("MSH-18"));
            return acuse;
        } catch (IOException e) {
            throw new HL7Exception("No se pudo componer el acuse", e);
        }
    }

    /**
     * El mensaje tal y como llegó por el hilo.
     *
     * <p>HAPI lo deja en los metadatos. Si faltara —no debería—, se reserializa lo parseado antes que
     * quedarse sin archivar: una copia normalizada es peor que el original, pero mucho mejor que nada.
     */
    private static String crudoDe(java.util.Map<String, Object> metadatos, Message recibido) throws HL7Exception {
        Object crudo = metadatos.get(MetadataKeys.IN_RAW_MESSAGE);
        return crudo instanceof String texto && !texto.isBlank() ? texto : recibido.encode();
    }
}
