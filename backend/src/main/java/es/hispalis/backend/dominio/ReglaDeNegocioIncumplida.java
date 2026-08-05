package es.hispalis.backend.dominio;

/**
 * Una operación bien formada que el laboratorio no puede permitir.
 *
 * <p>Se distingue de {@link ConflictoDeNegocio} en qué está mal: un conflicto es chocar con algo que
 * ya existe —un NHC repetido—, mientras que esto es <strong>una acción que no procede</strong> por el
 * estado en que están las cosas: informar el resultado de una muestra que se rechazó.
 *
 * <p>Se traduce a {@code 422 Unprocessable Entity}, que es el código que la propia especificación de
 * FHIR reserva para cuando <em>«el recurso propuesto viola las reglas de negocio del servidor»</em>.
 */
public class ReglaDeNegocioIncumplida extends ErrorDeDominio {

    public ReglaDeNegocioIncumplida(String mensaje) {
        super(mensaje);
    }
}
