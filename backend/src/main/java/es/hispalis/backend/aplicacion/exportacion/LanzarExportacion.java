package es.hispalis.backend.aplicacion.exportacion;

import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abre el trabajo y devuelve, para que el cliente reciba su {@code 202} sin esperar a nada.
 *
 * <p><strong>El trabajo se confirma antes de arrancarlo</strong>, y ese orden importa: si se lanzara
 * el hilo dentro de la transacción, el ejecutor podría empezar a buscar un trabajo que todavía no está
 * en la base de datos y darlo por inexistente. Es la misma trampa del <em>outbox</em> con otro
 * disfraz, y se resuelve igual: primero se apunta, luego se hace.
 *
 * <p>El {@code transactionTime} se fija <strong>aquí</strong> y no al terminar. Es el valor con el que
 * el cliente pedirá su siguiente carga incremental, y ponerlo al final dejaría un hueco: todo lo que
 * se escribiera mientras la exportación corría quedaría por debajo del corte y nadie volvería a
 * pedirlo.
 */
public class LanzarExportacion {

    private static final Logger LOG = LoggerFactory.getLogger(LanzarExportacion.class);

    private final RepositorioDeExportaciones trabajos;
    private final EjecutarExportacion ejecutar;
    private final Executor enSegundoPlano;

    public LanzarExportacion(
            RepositorioDeExportaciones trabajos, EjecutarExportacion ejecutar, Executor enSegundoPlano) {
        this.trabajos = trabajos;
        this.ejecutar = ejecutar;
        this.enSegundoPlano = enSegundoPlano;
    }

    /**
     * @param cohorte el {@code Group/…} que se exporta
     * @param solicitante el sujeto del testigo, para poder decir años después quién se llevó qué
     * @param tipos lo pedido con {@code _type}, o vacío para todo lo exportable
     * @return el trabajo abierto, cuyo id es lo que viaja en {@code Content-Location}
     */
    public TrabajoDeExportacion ejecutar(String cohorte, Optional<String> solicitante, List<String> tipos) {
        // Sin transacción envolvente, y a propósito: el `guardar` se confirma solo, antes de que el
        // ejecutor arranque. Dentro de una transacción, el hilo de fondo podría buscar un trabajo que
        // todavía no está confirmado y darlo por inexistente. Es la trampa del outbox con otro
        // disfraz — y aquí no hace falta atomicidad con nada más: abrir un trabajo es un solo INSERT.
        TrabajoDeExportacion trabajo = TrabajoDeExportacion.abrir(cohorte, solicitante, Instant.now());
        trabajos.guardar(trabajo);

        LOG.info(
                "Exportación {} abierta sobre {} a petición de {}.",
                trabajo.id(),
                cohorte,
                solicitante.orElse("un cliente sin identificar"));

        enSegundoPlano.execute(() -> ejecutar.ejecutar(trabajo.id(), tipos));
        return trabajo;
    }
}
