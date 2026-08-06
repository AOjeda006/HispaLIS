package es.hispalis.integracion.almacen;

import es.hispalis.integracion.hl7.CabeceraMsh;
import java.time.Instant;
import java.util.UUID;

/**
 * Un mensaje tal y como llegó, con lo que hace falta para encontrarlo después.
 *
 * <p>El {@code crudo} es <strong>el original íntegro</strong>: no normalizado, no reordenado, no
 * «limpiado». Es la única prueba de qué mandó el emisor cuando dentro de seis meses alguien pregunte
 * por qué un paciente tiene mal el apellido, y una copia arreglada no responde a esa pregunta.
 *
 * @param id identidad del mensaje en el almacén, del motor y no del emisor
 * @param cabecera lo que dice el {@code MSH}
 * @param nhc el paciente al que se refiere, si se pudo leer; metadato indexable
 * @param episodio el número de episodio ({@code PV1-19}), si venía
 * @param crudo el mensaje entero, ya decodificado al juego que declaraba
 * @param recibidoEn cuándo entró por el canal
 */
public record MensajeEntrante(
        UUID id, CabeceraMsh cabecera, String nhc, String episodio, String crudo, Instant recibidoEn) {

    public static MensajeEntrante recienLlegado(CabeceraMsh cabecera, String nhc, String episodio, String crudo) {
        return new MensajeEntrante(UUID.randomUUID(), cabecera, nhc, episodio, crudo, Instant.now());
    }
}
