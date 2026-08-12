package es.hispalis.backend.dominio.exportacion;

import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Una exportación masiva, desde que se pide hasta que se borra.
 *
 * <p>Podría parecer que esto no es un agregado sino un detalle de infraestructura: un trabajo en
 * segundo plano que escribe unos ficheros. Lo que lo convierte en agregado es <strong>lo que hay que
 * poder afirmar sobre él</strong>: que unos datos de población salieron del laboratorio, cuándo, a
 * petición de quién, y —sobre todo— <em>que ya no están</em>. Eso no es un fichero en un disco: es un
 * hecho del que el laboratorio responde, y tiene invariantes propios.
 *
 * <p>Los tres que se protegen aquí:
 *
 * <ol>
 *   <li><strong>El corte temporal se fija al empezar y no se toca.</strong> Es lo que un cliente usará
 *       como {@code _since} de su siguiente carga; recalcularlo al terminar dejaría un hueco de
 *       recursos que se escribieron mientras la exportación corría y que nadie volvería a pedir.
 *   <li><strong>Los ficheros solo existen si el trabajo terminó.</strong> Entregar un manifiesto a
 *       medias es entregar una cohorte incompleta con aspecto de completa, y en vigilancia
 *       epidemiológica eso se parece mucho a un brote más pequeño de lo que es.
 *   <li><strong>La caducidad se pone al terminar, no al pedir.</strong> Un trabajo que tarda diez
 *       minutos no puede comerse el plazo de descarga del cliente.
 * </ol>
 *
 * <p>Y uno que se protege por omisión: <strong>aquí no hay ni un dato de paciente.</strong> Ni en el
 * agregado, ni en el nombre del fichero, ni en el billete de descarga. Lo que se guarda es a qué
 * cohorte pertenece la exportación y qué tipos de recurso salieron.
 */
public class TrabajoDeExportacion {

    /**
     * Un fichero NDJSON ya escrito.
     *
     * @param billete el identificador opaco con el que se descarga. Es de un solo significado y no
     *     dice nada de nadie: una URL de descarga acaba en el log del proxy y en el historial
     * @param tipoDeRecurso qué hay dentro, que es lo que el manifiesto tiene que declarar
     * @param nombre cómo se llama en el disco. Se deriva del tipo, nunca de la cohorte ni del paciente
     * @param recursos cuántas líneas trae
     */
    public record Fichero(String billete, String tipoDeRecurso, String nombre, long recursos) {}

    private final UUID id;
    private final String cohorte;
    private final Optional<String> solicitante;
    private final Instant corte;

    private EstadoDeExportacion estado;
    private final List<Fichero> ficheros;
    private Optional<Instant> caducaEn;
    private Optional<String> motivoDelFallo;

    private TrabajoDeExportacion(
            UUID id,
            String cohorte,
            Optional<String> solicitante,
            Instant corte,
            EstadoDeExportacion estado,
            List<Fichero> ficheros,
            Optional<Instant> caducaEn,
            Optional<String> motivoDelFallo) {
        this.id = id;
        this.cohorte = cohorte;
        this.solicitante = solicitante;
        this.corte = corte;
        this.estado = estado;
        this.ficheros = new ArrayList<>(ficheros);
        this.caducaEn = caducaEn;
        this.motivoDelFallo = motivoDelFallo;
    }

    /**
     * Abre un trabajo.
     *
     * @param cohorte el id del {@code Group} que se exporta
     * @param solicitante el sujeto del testigo que lo pidió, o vacío si la seguridad está apagada
     * @param corte el instante que el manifiesto declarará como {@code transactionTime}
     */
    public static TrabajoDeExportacion abrir(String cohorte, Optional<String> solicitante, Instant corte) {
        return new TrabajoDeExportacion(
                UUID.randomUUID(),
                cohorte,
                solicitante,
                corte,
                EstadoDeExportacion.EN_CURSO,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    /** Reconstruye el agregado desde la persistencia. */
    public static TrabajoDeExportacion reconstruir(
            UUID id,
            String cohorte,
            Optional<String> solicitante,
            Instant corte,
            EstadoDeExportacion estado,
            List<Fichero> ficheros,
            Optional<Instant> caducaEn,
            Optional<String> motivoDelFallo) {
        return new TrabajoDeExportacion(id, cohorte, solicitante, corte, estado, ficheros, caducaEn, motivoDelFallo);
    }

    /**
     * El trabajo acabó bien y los ficheros están escritos.
     *
     * @param escritos los NDJSON producidos
     * @param cuanto cuánto tiempo se van a poder descargar, contado desde ahora
     */
    public void terminar(List<Fichero> escritos, Duration cuanto, Instant ahora) {
        exigirEnCurso("terminar");
        ficheros.clear();
        ficheros.addAll(escritos);
        caducaEn = Optional.of(ahora.plus(cuanto));
        estado = EstadoDeExportacion.TERMINADA;
    }

    /**
     * El trabajo falló.
     *
     * <p>El motivo es técnico y se escribe pensando en quién lo va a leer: aquí no hay nada del
     * paciente que contar, y una exportación no falla por lo que ponga en los datos.
     */
    public void fallar(String motivo) {
        exigirEnCurso("dar por fallida");
        motivoDelFallo = Optional.of(motivo);
        estado = EstadoDeExportacion.FALLIDA;
    }

    /**
     * El cliente dijo que ya está, o el plazo se acabó.
     *
     * <p>No distingue las dos cosas a propósito: desde el punto de vista del dato, lo que importa es
     * que ya no se sirve y que en el disco no queda nada. Quién lo cerró es información de operación,
     * y vive en el log.
     */
    public void cerrar() {
        ficheros.clear();
        caducaEn = Optional.empty();
        estado = EstadoDeExportacion.CERRADA;
    }

    /** ¿Se le ha pasado el plazo de descarga? Un trabajo sin caducidad todavía está trabajando. */
    public boolean haCaducado(Instant ahora) {
        return caducaEn.map(limite -> !ahora.isBefore(limite)).orElse(false);
    }

    /**
     * Los ficheros, que solo existen si el trabajo terminó.
     *
     * @throws ReglaDeNegocioIncumplida si se piden antes de tiempo
     */
    public List<Fichero> ficheros() {
        if (estado != EstadoDeExportacion.TERMINADA) {
            throw new ReglaDeNegocioIncumplida(
                    "Una exportación en estado " + estado + " no tiene ficheros que entregar. Un manifiesto a "
                            + "medias entregaría una cohorte incompleta con aspecto de completa.");
        }
        return List.copyOf(ficheros);
    }

    /** El fichero de un billete, si es de este trabajo y el trabajo sigue vivo. */
    public Optional<Fichero> ficheroDelBillete(String billete) {
        return estado == EstadoDeExportacion.TERMINADA
                ? ficheros.stream()
                        .filter(fichero -> fichero.billete().equals(billete))
                        .findFirst()
                : Optional.empty();
    }

    private void exigirEnCurso(String queSeIntentaba) {
        if (estado != EstadoDeExportacion.EN_CURSO) {
            throw new ReglaDeNegocioIncumplida(
                    "No se puede " + queSeIntentaba + " una exportación que ya está " + estado + ".");
        }
    }

    public UUID id() {
        return id;
    }

    public String cohorte() {
        return cohorte;
    }

    public Optional<String> solicitante() {
        return solicitante;
    }

    public Instant corte() {
        return corte;
    }

    public EstadoDeExportacion estado() {
        return estado;
    }

    public Optional<Instant> caducaEn() {
        return caducaEn;
    }

    public Optional<String> motivoDelFallo() {
        return motivoDelFallo;
    }

    /** Los ficheros tal y como están, sin exigir estado. Para persistir y para borrar. */
    public List<Fichero> ficherosSinExigirEstado() {
        return List.copyOf(ficheros);
    }
}
