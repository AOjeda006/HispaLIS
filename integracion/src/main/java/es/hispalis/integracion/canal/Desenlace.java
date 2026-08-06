package es.hispalis.integracion.canal;

/**
 * Qué ha pasado con un mensaje, y qué acuse le corresponde.
 *
 * <p>La correspondencia con {@code MSA-1} no es decorativa: un emisor v2 <strong>actúa</strong> según
 * el acuse. Con {@code AA} lo da por entregado y no vuelve; con {@code AE} suele mandarlo a una
 * bandeja de errores para que lo mire una persona; con {@code AR} entiende que el problema es del
 * mensaje y no reintenta. Devolver el que no toca es peor que no responder.
 *
 * @param resultado qué se hizo
 * @param detalle la referencia producida, o el motivo del rechazo
 */
public record Desenlace(Resultado resultado, String detalle) {

    public static Desenlace aceptado(String referenciaProducida) {
        return new Desenlace(Resultado.ACEPTADO, referenciaProducida);
    }

    /** Ya se había aplicado. Se acusa {@code AA}: repetir la escritura sería duplicar al paciente. */
    public static Desenlace duplicado(String detalle) {
        return new Desenlace(Resultado.DUPLICADO, detalle);
    }

    /**
     * El mensaje llegó bien pero no procede aplicarlo — un {@code A08} de alguien que no existe, un
     * {@code PID} sin NHC. Es {@code AE}: hay algo que corregir, y lo corrige una persona.
     */
    public static Desenlace errorDeAplicacion(String motivo) {
        return new Desenlace(Resultado.ERROR_DE_APLICACION, motivo);
    }

    /**
     * El mensaje no se acepta y reintentarlo no va a servir: charset ilegible, estructura que no
     * existe. Es {@code AR}.
     */
    public static Desenlace rechazado(String motivo) {
        return new Desenlace(Resultado.RECHAZADO, motivo);
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
