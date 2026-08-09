package es.hispalis.backend.dominio.resultado;

/**
 * No se ha podido responder a «¿este resultado es crítico?».
 *
 * <p><strong>No es un {@link es.hispalis.backend.dominio.ErrorDeDominio}</strong>, y la distinción es
 * el motivo de que esta clase exista: no hay ningún invariante incumplido ni nada mal en lo que llegó.
 * Lo que pasa es que la pregunta se ha quedado sin contestar — el servidor de terminología no está, o
 * el resultado viene en una unidad que no es la del umbral publicado.
 *
 * <p><strong>Por qué se lanza en vez de contestar {@code false}.</strong> En todo lo demás, este
 * laboratorio degrada cuando la terminología no contesta: publica el código sin nombre y deja de
 * rechazar pruebas desconocidas, porque el nombre es presentación y el código es el dato. Aquí no
 * vale: «no es crítico» no es una degradación de «no lo sé», es una respuesta distinta, y es la única
 * respuesta de este sistema que se paga con una llamada de teléfono que no se hace. Quien pregunte
 * tiene que enterarse de que no hay respuesta.
 */
public class NoSeSabeSiEsCritico extends RuntimeException {

    public NoSeSabeSiEsCritico(String mensaje) {
        super(mensaje);
    }
}
