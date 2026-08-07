package es.hispalis.backend.infraestructura.bus;

import es.hispalis.lab.v1.HechoDeEspecimen;
import es.hispalis.lab.v1.HechoDeInforme;
import es.hispalis.lab.v1.HechoDePeticion;
import es.hispalis.lab.v1.HechoDeResultado;
import org.apache.avro.Schema;

/**
 * Los cuatro tópicos del laboratorio (§11 del diseño), con el esquema que gobierna cada uno.
 *
 * <p>El {@code .v1} del nombre es parte del contrato y no un adorno: cuando haga falta un cambio que
 * el registro no acepte como compatible hacia atrás, lo que se hace es un tópico {@code .v2} y una
 * migración de consumidores, no una versión rota del mismo. La versión del <em>esquema</em> cubre lo
 * que se puede evolucionar sin romper; la del <em>tópico</em>, lo que no.
 *
 * <p>El esquema no se lee de un fichero en ejecución: lo lleva dentro la clase generada, y la clase
 * generada sale del {@code .avsc} de {@code src/main/avro}. Así solo hay una copia del contrato.
 */
public enum Topico {

    /** Trabajo pedido: líneas de petición que entran y líneas que el laboratorio no va a hacer. */
    PETICIONES("lab.peticiones.v1", HechoDePeticion.getClassSchema()),

    /** Muestras que llegan al laboratorio. */
    ESPECIMENES("lab.especimenes.v1", HechoDeEspecimen.getClassSchema()),

    /** Cifras medidas y cifras firmadas. Nunca la cifra en sí. */
    RESULTADOS("lab.resultados.v1", HechoDeResultado.getClassSchema()),

    /** Informes emitidos. De aquí cuelgan el {@code ORU^R01} saliente y el notificador EDO. */
    INFORMES("lab.informes.v1", HechoDeInforme.getClassSchema());

    private final String nombre;
    private final Schema esquema;

    Topico(String nombre, Schema esquema) {
        this.nombre = nombre;
        this.esquema = esquema;
    }

    public String nombre() {
        return nombre;
    }

    public Schema esquema() {
        return esquema;
    }

    /**
     * El nombre bajo el que vive el esquema en el registro.
     *
     * <p>Es {@code <tópico>-value}, que es la {@code TopicNameStrategy} por defecto de Confluent. Se
     * calcula aquí, y no se escribe a mano en la configuración, porque el serializador lo va a
     * componer exactamente así: escribirlo dos veces es tener dos sitios donde equivocarse y un
     * fallo que solo aparece al publicar el primer mensaje.
     */
    public String sujetoEnElRegistro() {
        return nombre + "-value";
    }
}
