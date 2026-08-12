package es.hispalis.backend.aplicacion.exportacion;

import es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros;
import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El barrendero: quita del disco lo que ya no debería estar.
 *
 * <p>Hace <strong>dos</strong> cosas, y la segunda es la que de verdad justifica que exista:
 *
 * <ol>
 *   <li><strong>Lo caducado.</strong> El cliente ordenado hace {@code DELETE} y no hace falta esperar;
 *       el que desaparece a mitad de la descarga, no. Sin barrido, el plazo del manifiesto sería una
 *       promesa que solo cumplen los educados.
 *   <li><strong>Los huérfanos.</strong> Ficheros en el disco cuyo trabajo ya no está en el registro.
 *       Es lo que queda tras un reinicio en mitad de una exportación, o tras un borrado a medias, y
 *       <strong>nadie los iba a reclamar nunca</strong>: no hay sondeo que los mencione ni cliente que
 *       pregunte por ellos. Un volcado de población olvidado en una carpeta es exactamente el activo
 *       que este proyecto lleva dos hitos evitando.
 * </ol>
 *
 * <p>Con una salvedad que la IG de Bulk Data pone por escrito y que aquí se respeta: <strong>no se
 * borra lo que un cliente esté descargando</strong>. El sistema de ficheros lo resuelve solo —un
 * fichero abierto sigue leyéndose aunque se borre su entrada— así que la descarga en curso termina y
 * la siguiente no encuentra nada, que es lo correcto.
 */
public class BarrerExportaciones {

    private static final Logger LOG = LoggerFactory.getLogger(BarrerExportaciones.class);

    private final RepositorioDeExportaciones trabajos;
    private final AlmacenDeFicheros almacen;
    private final CerrarExportacion cerrar;

    public BarrerExportaciones(
            RepositorioDeExportaciones trabajos, AlmacenDeFicheros almacen, CerrarExportacion cerrar) {
        this.trabajos = trabajos;
        this.almacen = almacen;
        this.cerrar = cerrar;
    }

    public void ejecutar() {
        Instant ahora = Instant.now();

        for (TrabajoDeExportacion caducada : trabajos.caducadas(ahora)) {
            cerrar.ejecutar(caducada.id(), "se le pasó el plazo de descarga");
        }

        Set<UUID> conRegistro = trabajos.vivas();
        for (UUID enElDisco : almacen.trabajosConFicheros()) {
            if (!conRegistro.contains(enElDisco)) {
                // Sale como aviso y no como línea de depuración: un huérfano significa que algo se
                // interrumpió, y aunque el barrendero lo arregle, alguien debería saber que pasó.
                LOG.warn("Exportación {} huérfana en el disco: no hay trabajo que la reclame. Se borra.", enElDisco);
                almacen.borrar(enElDisco);
            }
        }
    }
}
