package es.hispalis.integracion.hl7;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * El juego de caracteres que el mensaje declara en {@code MSH-18}.
 *
 * <p>Este es <strong>el punto exacto donde se rompen las tuberías v2 en España</strong>, y se rompen
 * en silencio: decodificar como UTF-8 un mensaje que venía en {@code 8859/1} no lanza ninguna
 * excepción — produce {@code MU?OZ} y sigue adelante. Un test que solo compruebe que no hubo error
 * pasa; el paciente se llama otra cosa a partir de ahí.
 *
 * <p>La decodificación de bytes la hace HAPI con {@code ExtendedMinLowerLayerProtocol}, que lee
 * {@code MSH-18} antes de convertir. Lo que hace esta clase es la otra mitad: <strong>comprobar que
 * lo declarado es algo que sabemos leer</strong>. Si no lo es, HAPI ya ha decodificado con el juego
 * por defecto y lo que tenemos entre manos es basura silenciosa — así que el canal rechaza el
 * mensaje en vez de escribir un nombre corrupto en la historia de alguien.
 */
public final class CharsetDeclarado {

    /**
     * Lo que se usa cuando {@code MSH-18} viene vacío, que es legal.
     *
     * <p>El estándar dice que la ausencia significa el juego por defecto del acuerdo entre las
     * partes. En España ese acuerdo es, casi siempre, {@code ISO-8859-1}: los HIS que no declaran
     * charset son los antiguos, y los antiguos mandan latín-1. Elegir UTF-8 aquí «por moderno»
     * convertiría cada {@code Ñ} en dos caracteres.
     */
    public static final Charset POR_DEFECTO = StandardCharsets.ISO_8859_1;

    /**
     * Los valores de la tabla 0211 que este laboratorio acepta, con el nombre exacto del estándar.
     *
     * <p>Es una <strong>lista corta y explícita</strong>, no la tabla entera. HAPI sabe decodificar
     * bastantes más —cirílico, japonés, hebreo—, y aceptarlos aquí sería fingir un soporte que nadie
     * ha probado: un laboratorio de Sevilla que reciba un mensaje declarado en {@code 8859/8} tiene
     * un problema de configuración en el emisor, no un paciente israelí.
     *
     * <p>Los literales coinciden con los que usa HAPI para decodificar, y hay un test que lo
     * comprueba de ida y vuelta: si divergieran, HAPI leería con un juego y nosotros validaríamos
     * contra otro, que es la peor combinación posible.
     */
    private static final Map<String, Charset> ACEPTADOS = Map.of(
            "ASCII", StandardCharsets.US_ASCII,
            "8859/1", StandardCharsets.ISO_8859_1,
            "8859/15", Charset.forName("ISO-8859-15"),
            "UNICODE", StandardCharsets.UTF_8,
            "UNICODE UTF-8", StandardCharsets.UTF_8);

    /** {@code U+FFFD}, con su escape: el carácter en el fuente es justo el que nadie sabe leer. */
    private static final char REEMPLAZO = '\uFFFD';

    private final String declarado;
    private final Charset juego;

    private CharsetDeclarado(String declarado, Charset juego) {
        this.declarado = declarado;
        this.juego = juego;
    }

    /**
     * Interpreta el valor de {@code MSH-18}.
     *
     * @param msh18 el valor tal y como viene, posiblemente vacío o nulo
     * @return el juego resuelto
     * @throws CharsetNoSoportado si se declara uno que esta pasarela no sabe leer
     */
    public static CharsetDeclarado de(String msh18) {
        if (msh18 == null || msh18.isBlank()) {
            return new CharsetDeclarado("", POR_DEFECTO);
        }
        String literal = msh18.strip();
        Charset juego = ACEPTADOS.get(literal.toUpperCase());
        if (juego == null) {
            throw new CharsetNoSoportado(literal);
        }
        return new CharsetDeclarado(literal, juego);
    }

    /** Los literales que se aceptan, para el mensaje de error y para el test que los cruza con HAPI. */
    public static java.util.Set<String> aceptados() {
        return ACEPTADOS.keySet();
    }

    /**
     * Comprueba que lo decodificado no lleva marcas de haberse decodificado mal.
     *
     * <p>El carácter de reemplazo {@code U+FFFD} no existe en ningún mensaje legítimo: lo pone el
     * decodificador de Java cuando encuentra bytes que no pertenecen al juego con el que está
     * leyendo. Si aparece, el emisor declaró una cosa y mandó otra, y lo que tenemos delante ya no es
     * su mensaje.
     *
     * <p><strong>Lo que esta red NO caza, y conviene saberlo:</strong> pilla los bytes latín-1 leídos
     * como UTF-8 y como ASCII —las dos direcciones que rompen a diario— pero <strong>no</strong> los
     * bytes UTF-8 leídos como latín-1: esa combinación produce {@code MUÃ±OZ}, que son caracteres
     * perfectamente válidos, y no hay forma de distinguirla de un nombre raro sin adivinar. Contra esa
     * solo vale que {@code MSH-18} diga la verdad, que es justo por lo que se exige y se archiva.
     *
     * @throws CharsetNoCuadra si el texto trae caracteres de reemplazo
     */
    public void exigirQueLoLeidoCuadre(String mensaje) {
        if (mensaje.indexOf(REEMPLAZO) >= 0) {
            throw new CharsetNoCuadra(declarado.isEmpty() ? "(MSH-18 vacío, se asumió 8859/1)" : declarado);
        }
    }

    /** El literal de {@code MSH-18}, vacío si no venía. */
    public String declarado() {
        return declarado;
    }

    /** Lo declarado, o vacío si el mensaje no lo dijo. Se guarda tal cual en el almacén. */
    public Optional<String> literal() {
        return declarado.isEmpty() ? Optional.empty() : Optional.of(declarado);
    }

    public Charset juego() {
        return juego;
    }

    /** Se levanta cuando el mensaje declara un juego y viene en otro. */
    public static final class CharsetNoCuadra extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CharsetNoCuadra(String declarado) {
            super(("El mensaje declara «%s» en MSH-18 pero trae bytes que no son de ese juego: al decodificarlo han "
                            + "aparecido caracteres de reemplazo. No se procesa — lo que llegue al laboratorio ya no "
                            + "sería el nombre del paciente.")
                    .formatted(declarado));
        }
    }

    /** Se levanta cuando {@code MSH-18} declara un juego que no sabemos leer. */
    public static final class CharsetNoSoportado extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CharsetNoSoportado(String declarado) {
            super(("El mensaje declara el juego de caracteres «%s» en MSH-18 y este laboratorio no lo acepta. "
                            + "Los admitidos son %s. No se procesa: lo que se haya decodificado ya está corrupto, y "
                            + "un nombre corrupto en una historia clínica es peor que un mensaje rechazado.")
                    .formatted(declarado, String.join(", ", ACEPTADOS.keySet())));
        }
    }
}
