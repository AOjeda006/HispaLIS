package es.hispalis.backend.dominio;

/**
 * Un dato correcto que choca con el estado actual del laboratorio: un NHC ya emitido, una petición
 * que ya se cerró. Se traduce a un {@code 409}.
 */
public class ConflictoDeNegocio extends ErrorDeDominio {

    public ConflictoDeNegocio(String mensaje) {
        super(mensaje);
    }
}
