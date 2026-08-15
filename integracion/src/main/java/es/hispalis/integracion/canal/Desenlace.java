package es.hispalis.integracion.canal;

/**
 * Qué ha pasado con un mensaje, y qué acuse le corresponde.
 *
 * <p>La correspondencia con {@code MSA-1} no es decorativa: un emisor v2 <strong>actúa</strong> según
 * el acuse. Con {@code AA} lo da por entregado y no vuelve; con {@code AE} suele mandarlo a una
 * bandeja de errores para que lo mire una persona; con {@code AR} entiende que el problema es del
 * mensaje y no reintenta. Devolver el que no toca es peor que no responder.
 *
 * <h2>Dos textos y no uno, desde el 2026-08-15</h2>
 *
 * <p>Porque tienen <strong>dos destinatarios distintos</strong> y hasta ahora compartían uno solo. El
 * {@code detalle} sale por el cable hacia un sistema ajeno; el {@code detalleTecnico} se queda en el
 * archivo del motor, que es interno y es donde lo busca quien diagnostica.
 *
 * <p>Lo descubrió el fuzzing: un byte nulo dentro del mensaje hace que PostgreSQL rechace el
 * {@code INSERT} del archivo, y el mensaje de esa excepción —que trae <strong>la sentencia SQL
 * entera</strong> y la versión del motor de base de datos— acababa dentro del {@code ERR} del acuse.
 * El HIS del hospital recibía el esquema del laboratorio por el puerto MLLP. Con un solo texto no hay
 * forma de arreglarlo sin perder el diagnóstico; con dos, cada cosa va donde tiene que ir.
 *
 * @param resultado qué se hizo
 * @param detalle lo que se le cuenta al emisor. <strong>Sale por el cable</strong>: aquí no va nunca
 *     el mensaje de una excepción inesperada
 * @param detalleTecnico lo que se guarda en el archivo del motor, para quien tenga que diagnosticar
 */
public record Desenlace(Resultado resultado, String detalle, String detalleTecnico) {

    /**
     * Lo que se le dice al emisor cuando algo se rompe por dentro.
     *
     * <p>Fijo y sin variables a propósito: el emisor no puede hacer nada con el detalle técnico, y lo
     * único que consigue mandárselo es sacar del laboratorio información de cómo está construido.
     */
    public static final String FALLO_INTERNO =
            "El motor no ha podido aplicar el mensaje por un fallo interno del laboratorio. Queda archivado "
                    + "con su motivo y es reprocesable; no hace falta reenviarlo.";

    public static Desenlace aceptado(String referenciaProducida) {
        return new Desenlace(Resultado.ACEPTADO, referenciaProducida, referenciaProducida);
    }

    /** Ya se había aplicado. Se acusa {@code AA}: repetir la escritura sería duplicar al paciente. */
    public static Desenlace duplicado(String detalle) {
        return new Desenlace(Resultado.DUPLICADO, detalle, detalle);
    }

    /**
     * El mensaje llegó bien pero no procede aplicarlo — un {@code A08} de alguien que no existe, un
     * {@code PID} sin NHC. Es {@code AE}: hay algo que corregir, y lo corrige una persona.
     *
     * <p>Aquí el motivo <strong>sí</strong> va hacia el emisor, y tiene que ir: es lo que le dice qué
     * arreglar. Son frases escritas para eso, no mensajes de excepción.
     */
    public static Desenlace errorDeAplicacion(String motivo) {
        return new Desenlace(Resultado.ERROR_DE_APLICACION, motivo, motivo);
    }

    /**
     * Se rompió algo que no se esperaba que se rompiera. El emisor recibe una frase fija y el archivo
     * se queda el motivo de verdad.
     */
    public static Desenlace falloInterno(String tecnico) {
        return new Desenlace(Resultado.ERROR_DE_APLICACION, FALLO_INTERNO, tecnico);
    }

    /**
     * El mensaje no se acepta y reintentarlo no va a servir: charset ilegible, estructura que no
     * existe. Es {@code AR}.
     */
    public static Desenlace rechazado(String motivo) {
        return new Desenlace(Resultado.RECHAZADO, motivo, motivo);
    }

    public boolean seAplico() {
        return resultado == Resultado.ACEPTADO;
    }

    /** El código de {@code MSA-1} que le corresponde. */
    public String codigoDeAcuse() {
        return switch (resultado) {
            case ACEPTADO, DUPLICADO -> "AA";
            case ERROR_DE_APLICACION -> "AE";
            case RECHAZADO -> "AR";
        };
    }

    public enum Resultado {
        ACEPTADO,
        DUPLICADO,
        ERROR_DE_APLICACION,
        RECHAZADO
    }
}
