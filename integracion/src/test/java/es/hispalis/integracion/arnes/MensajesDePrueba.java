package es.hispalis.integracion.arnes;

import java.util.ArrayList;
import java.util.List;

/**
 * Constructor de mensajes {@code ADT} para las pruebas, con los apellidos que rompen tuberías.
 *
 * <p>Los campos se colocan <strong>por índice</strong> y no concatenando barras a ojo. Contar
 * separadores a mano es cómo se acaba con un {@code PV1-19} en el {@code PV1-18} y un test que
 * comprueba otra cosa distinta de la que dice comprobar.
 */
public final class MensajesDePrueba {

    /** El apellido con eñe, el que más veces se ha perdido en una interfaz v2 española. */
    public static final String MUNOZ = "MUÑOZ DE LA TORRE";

    /** El de la tilde, y además compuesto: cuatro palabras, dos apellidos. */
    public static final String FERNANDEZ = "FERNÁNDEZ DE CÓRDOBA RUIZ";

    /** Corto, con eñe y con tilde a la vez. */
    public static final String PENA = "PEÑA ÁLVAREZ";

    private MensajesDePrueba() {
        // Utilidad.
    }

    /**
     * Un {@code ADT} completo.
     *
     * @param evento {@code A01} o {@code A08}
     * @param controlId {@code MSH-10}
     * @param nhc el número de historia, ocho dígitos
     * @param apellidos el nombre familiar <strong>completo</strong>, tal y como viaja en {@code PID-5.1}
     * @param nombreDePila los componentes de nombre de {@code PID-5}, p. ej. {@code "Begoña^María"}
     * @param charset lo que se declara en {@code MSH-18}
     */
    public static String adt(
            String evento, String controlId, String nhc, String apellidos, String nombreDePila, String charset) {
        return adt(evento, controlId, nhc, apellidos, nombreDePila, charset, "ADT_A01");
    }

    /** Igual, pero eligiendo qué código de estructura se declara en {@code MSH-9-3}. */
    public static String adt(
            String evento,
            String controlId,
            String nhc,
            String apellidos,
            String nombreDePila,
            String charset,
            String estructura) {
        String tipo = estructura == null || estructura.isBlank()
                ? "ADT^" + evento
                : "ADT^%s^%s".formatted(evento, estructura);
        return String.join(
                "\r",
                msh(tipo, controlId, charset),
                "EVN|%s|20260806120000".formatted(evento),
                pid(nhc, apellidos, nombreDePila),
                pv1());
    }

    private static String msh(String tipo, String controlId, String charset) {
        List<String> campos = campos(18);
        campos.set(0, "MSH");
        campos.set(1, "^~\\&");
        campos.set(2, "HIS_VIRGEN");
        campos.set(3, "H_VIRGEN_MACARENA");
        campos.set(4, "HISPALIS");
        campos.set(5, "LAB_SEVILLA");
        campos.set(6, "20260806120000");
        campos.set(8, tipo);
        campos.set(9, controlId);
        campos.set(10, "P");
        campos.set(11, "2.5.1");
        campos.set(16, "ES");
        campos.set(17, charset);
        return String.join("|", campos);
    }

    /**
     * {@code PID-3} lleva los tres identificadores que un paciente andaluz trae de verdad, cada uno
     * con su código de tipo de la tabla 0203: {@code MR} el NHC, {@code NI} el DNI y {@code JHN} el
     * NUHSA. El orden está puesto a propósito para que no coincida con el del perfil.
     */
    private static String pid(String nhc, String apellidos, String nombreDePila) {
        List<String> campos = campos(9);
        campos.set(0, "PID");
        campos.set(1, "1");
        campos.set(3, "%s^^^SAS^JHN~%s^^^HISPALIS^MR~12345678Z^^^MJU^NI".formatted("AN0123456789", nhc));
        campos.set(5, "%s^%s".formatted(apellidos, nombreDePila));
        campos.set(7, "19810314");
        campos.set(8, "F");
        return String.join("|", campos);
    }

    /** El episodio va en {@code PV1-19}, que es de donde lo saca el índice del almacén. */
    private static String pv1() {
        List<String> campos = campos(20);
        campos.set(0, "PV1");
        campos.set(1, "1");
        campos.set(2, "O");
        campos.set(3, "LAB^^^H_VIRGEN_MACARENA");
        campos.set(19, "EP20260806001");
        return String.join("|", campos);
    }

    /** Una lista de {@code n + 1} huecos vacíos: el 0 es el nombre del segmento. */
    private static List<String> campos(int ultimoIndice) {
        List<String> campos = new ArrayList<>(ultimoIndice + 1);
        for (int i = 0; i <= ultimoIndice; i++) {
            campos.add("");
        }
        return campos;
    }
}
