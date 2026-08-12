package es.hispalis.backend.infraestructura.exportacion;

import es.hispalis.backend.aplicacion.exportacion.BarrerExportaciones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * El que pasa cada rato a quitar del disco lo que ya no debería estar.
 *
 * <p>Es infraestructura pura: pone un reloj delante de {@link BarrerExportaciones}, que es quien sabe
 * qué se barre. La separación es la de siempre —la regla no depende de que la dispare un
 * {@code @Scheduled}, y por eso se puede probar sin esperar—, y aquí tiene un valor añadido: el test
 * de caducidad llama al caso de uso con un plazo de segundos en vez de simular quince minutos.
 *
 * <p>Se traga sus errores a propósito. Un barrido que falle y tumbe el planificador dejaría de borrar
 * <strong>para siempre</strong> y en silencio, que es el peor final posible para lo único que retira
 * volcados de población de un disco.
 */
public class BarrenderoDeExportaciones {

    private static final Logger LOG = LoggerFactory.getLogger(BarrenderoDeExportaciones.class);

    private final BarrerExportaciones barrer;

    public BarrenderoDeExportaciones(BarrerExportaciones barrer) {
        this.barrer = barrer;
    }

    @Scheduled(fixedDelayString = "${hispalis.exportacion.barrido}")
    public void pasar() {
        try {
            barrer.ejecutar();
        } catch (RuntimeException fallo) {
            LOG.error("El barrido de exportaciones ha fallado. Se reintentará en la siguiente vuelta.", fallo);
        }
    }
}
