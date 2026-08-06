package es.hispalis.integracion.almacen;

import java.time.Instant;
import java.util.UUID;

/**
 * Un mensaje tal y como está guardado en el archivo del motor.
 *
 * <p>Es lo que devuelve la consulta de la DLQ y lo que necesita el reproceso: el original íntegro
 * para volver a aplicarlo, y los metadatos para que quien mira la bandeja sepa qué está mirando sin
 * tener que leer un mensaje v2 a ojo.
 *
 * @param id identidad en el almacén
 * @param aplicacionEmisora {@code MSH-3}
 * @param instalacionEmisora {@code MSH-4}
 * @param controlId {@code MSH-10}
 * @param tipoYEvento {@code ADT^A01}, {@code OML^O21}…
 * @param nhc el paciente, si se pudo leer
 * @param recibidoEn cuándo entró
 * @param estado {@code RECIBIDO}, {@code PROCESADO} o {@code RECHAZADO}
 * @param detalle la referencia producida, o el motivo del rechazo
 * @param intentos cuántas veces se ha intentado aplicar
 * @param crudo el original íntegro
 */
public record MensajeArchivado(
        UUID id,
        String aplicacionEmisora,
        String instalacionEmisora,
        String controlId,
        String tipoYEvento,
        String nhc,
        Instant recibidoEn,
        String estado,
        String detalle,
        int intentos,
        String crudo) {}
