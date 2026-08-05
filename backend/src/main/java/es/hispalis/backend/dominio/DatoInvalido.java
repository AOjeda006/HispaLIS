package es.hispalis.backend.dominio;

/** Un dato que llega mal formado y que el núcleo no puede aceptar. Se traduce a un {@code 400}. */
public class DatoInvalido extends ErrorDeDominio {

    public DatoInvalido(String mensaje) {
        super(mensaje);
    }
}
