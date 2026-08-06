package es.hispalis.integracion.canal;

import ca.uhn.hl7v2.model.Message;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.hl7.CabeceraMsh;

/**
 * Un canal del motor, con la estructura de siempre: <strong>origen → filtro → transformador →
 * destino</strong>.
 *
 * <p>Las cuatro partes están repartidas así, y el reparto es deliberado:
 *
 * <ul>
 *   <li><strong>Origen:</strong> no es del canal. Lo pone el listener MLLP, que es uno para todos —
 *       un canal no abre puertos.
 *   <li><strong>Filtro:</strong> {@link #acepta(CabeceraMsh)}. Decide con la cabecera y nada más, sin
 *       mirar el contenido: si hiciera falta leer un {@code PID} para saber si el mensaje es tuyo, el
 *       enrutado dependería de datos clínicos.
 *   <li><strong>Transformador y destino:</strong> {@link #procesar}. El transformador es una clase
 *       aparte y sin estado, para poder probarlo sin levantar nada.
 * </ul>
 *
 * <p>Lo que un canal <strong>no hace</strong>: guardar el original, deduplicar ni acusar recibo. Esas
 * tres son garantías del motor y valen para todos los canales; dejarlas en cada canal sería confiar
 * en que el siguiente que se escriba se acuerde de las tres.
 */
public interface Canal {

    /** Nombre corto, para el almacén y los mensajes de error. */
    String nombre();

    /** El filtro. Solo la cabecera: el enrutado no mira datos clínicos. */
    boolean acepta(CabeceraMsh cabecera);

    /**
     * Los metadatos con los que el original quedará localizable en el almacén.
     *
     * <p>Los saca el canal y no el motor porque dónde vive el paciente depende del tipo de mensaje, y
     * el almacén tiene que seguir siendo genérico. Nunca lanza: un mensaje del que no se puede sacar
     * el paciente <strong>igualmente se guarda</strong> — sin índice, pero se guarda.
     */
    Indices indices(Message recibido);

    /**
     * Transforma y entrega en el destino.
     *
     * @param mensaje el original ya registrado en el almacén
     * @param recibido el mismo mensaje, parseado
     * @return qué se hizo con él
     */
    Desenlace procesar(MensajeEntrante mensaje, Message recibido);

    /**
     * Por dónde se busca un mensaje en el archivo.
     *
     * @param nhc el paciente, o {@code null} si no se pudo leer
     * @param episodio el episodio ({@code PV1-19}), o {@code null}
     */
    record Indices(String nhc, String episodio) {

        public static final Indices NINGUNO = new Indices(null, null);
    }
}
