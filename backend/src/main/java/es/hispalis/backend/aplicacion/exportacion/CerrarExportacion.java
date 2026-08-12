package es.hispalis.backend.aplicacion.exportacion;

import es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros;
import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retira una exportación: los ficheros del disco y el trabajo de la vista.
 *
 * <p>Sirve para las dos formas de acabar, y no las distingue: el {@code DELETE} del cliente ordenado y
 * el barrido de lo que se pasó de plazo. Desde el punto de vista del dato son lo mismo —ya no hay
 * volcado y el sondeo contesta {@code 404}— y quién lo cerró vive en el log, que es donde se mira
 * cuando esa pregunta importa.
 *
 * <p><strong>Se borra el fichero antes de cerrar el trabajo</strong>, no después. Si el orden fuera el
 * contrario y algo fallara en medio, quedaría un NDJSON en el disco sin trabajo que lo reclame — un
 * huérfano invisible. Al revés, lo peor que pasa es un trabajo cerrado cuyo borrado hay que repetir, y
 * eso el barrendero lo arregla solo.
 */
public class CerrarExportacion {

    private static final Logger LOG = LoggerFactory.getLogger(CerrarExportacion.class);

    private final RepositorioDeExportaciones trabajos;
    private final AlmacenDeFicheros almacen;

    public CerrarExportacion(RepositorioDeExportaciones trabajos, AlmacenDeFicheros almacen) {
        this.trabajos = trabajos;
        this.almacen = almacen;
    }

    /** @return {@code true} si había algo que cerrar */
    @Transactional
    public boolean ejecutar(UUID trabajoId, String porQue) {
        return trabajos.buscar(trabajoId)
                .map(trabajo -> {
                    cerrar(trabajo, porQue);
                    return true;
                })
                .orElse(false);
    }

    private void cerrar(TrabajoDeExportacion trabajo, String porQue) {
        almacen.borrar(trabajo.id());
        trabajo.cerrar();
        trabajos.guardar(trabajo);
        LOG.info("Exportación {} de {} retirada del disco: {}.", trabajo.id(), trabajo.cohorte(), porQue);
    }
}
