package es.hispalis.backend.infraestructura.edo;

import es.hispalis.backend.aplicacion.edo.AbrirNotificacionEdo;
import es.hispalis.backend.aplicacion.edo.EnviarNotificacionEdo;
import es.hispalis.backend.dominio.edo.NotificacionEdo;
import es.hispalis.backend.dominio.edo.RepositorioDeNotificacionesEdo;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import es.hispalis.backend.infraestructura.edo.HechosDeclarables.HechoPendiente;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * El notificador EDO: dos vueltas, y las dos cuelgan del hecho.
 *
 * <h2>Por qué no está dentro de {@code ValidarResultado}</h2>
 *
 * <p>Es el criterio del ítem 48 y el mismo del {@code ORU} saliente (ítem 28). Si declarar fuese una
 * línea dentro del caso de uso que valida, con Salud Pública caída habría dos salidas y las dos malas:
 * o el resultado no se valida —el laboratorio deja de funcionar porque una administración no
 * contesta—, o se valida y la declaración se pierde sin que quede constancia. Colgándolo del hecho, el
 * resultado se valida siempre y la declaración se reintenta cuando el destinatario vuelva.
 *
 * <h2>Abrir y enviar son dos pasos, no uno</h2>
 *
 * <ol>
 *   <li><strong>Abrir.</strong> Se recorre el {@code outbox} y cada {@code RESULTADO_DECLARABLE} se
 *       convierte en una declaración con su plazo. Esto pasa <em>aunque el destinatario esté
 *       apagado</em>: la obligación existe desde que el resultado se validó, no desde que alguien
 *       coge el teléfono.
 *   <li><strong>Enviar.</strong> Se intentan las que siguen abiertas, de la que antes vence a la que
 *       después. Cada intento se cuenta, y pasados {@code intentos} se deja de intentar sola.
 * </ol>
 *
 * <p><strong>El corte no borra la obligación</strong>, al revés que el del ítem 44 —donde una
 * suscripción cortada deja de recibir y ya está—. Aquí la declaración se queda abierta y vencida, que
 * es como tiene que verse: un incumplimiento que espera a que alguien lo resuelva, no una fila que
 * desaparece. Lo que se corta es el reintento automático, porque llamar cada segundo a una puerta
 * cerrada no declara nada y llena el log.
 */
public class NotificadorEdo {

    private static final Logger LOG = LoggerFactory.getLogger(NotificadorEdo.class);

    private final HechosDeclarables hechos;
    private final AbrirNotificacionEdo abrir;
    private final EnviarNotificacionEdo enviar;
    private final RepositorioDeNotificacionesEdo declaraciones;
    private final PropiedadesDelSvea propiedades;

    /** Para no repetir el mismo aviso en cada vuelta mientras el destinatario siga sin estar. */
    private boolean avisadoDeQueNoHayDestino;

    NotificadorEdo(
            HechosDeclarables hechos,
            AbrirNotificacionEdo abrir,
            EnviarNotificacionEdo enviar,
            RepositorioDeNotificacionesEdo declaraciones,
            PropiedadesDelSvea propiedades) {
        this.hechos = hechos;
        this.abrir = abrir;
        this.enviar = enviar;
        this.declaraciones = declaraciones;
        this.propiedades = propiedades;
    }

    /**
     * La vuelta periódica. Se traga lo que reviente <strong>a propósito</strong>: dejarlo subir solo
     * conseguiría que el planificador registrara la excepción y volviera a llamar igual.
     */
    @Scheduled(
            fixedDelayString = "${hispalis.edo.intervalo:PT5S}",
            initialDelayString = "${hispalis.edo.intervalo:PT5S}")
    void unaVuelta() {
        try {
            abrirLasNuevas();
            enviarLasAbiertas();
        } catch (RuntimeException e) {
            LOG.warn("La vuelta del notificador EDO ha fallado entera; se reintenta. Causa: {}", e.toString());
        }
    }

    /** Paso 1: del hecho a la declaración. No habla con nadie de fuera. */
    void abrirLasNuevas() {
        for (HechoPendiente hecho : hechos.pendientes(propiedades.tanda())) {
            if (hecho.tipo() != TipoDeHecho.RESULTADO_DECLARABLE) {
                // Por el outbox pasa todo lo que el laboratorio cuenta de sí mismo y a este consumidor
                // solo le interesa un tipo. Se descartan explícitamente para que el desplazamiento
                // avance: dejarlos sin anotar haría que la consulta los arrastrase para siempre.
                hechos.anotarDescartado(hecho.id(), "No es un resultado declarable: " + hecho.tipo());
                continue;
            }
            if (hecho.observacion() == null) {
                hechos.anotarDescartado(
                        hecho.id(), "El hecho RESULTADO_DECLARABLE no trae `observationRef` en su carga.");
                continue;
            }
            atender(hecho);
        }
    }

    private void atender(HechoPendiente hecho) {
        UUID resultadoId = UUID.fromString(
                hecho.observacion().substring(hecho.observacion().indexOf('/') + 1));
        // El plazo se cuenta desde que ocurrió el hecho, que es cuando se validó el resultado. Con
        // `Instant.now()` una cola de dos horas regalaría dos horas de plazo legal.
        abrir.ejecutar(resultadoId, hecho.ocurridoEn())
                .ifPresentOrElse(
                        declaracion -> hechos.anotarAtendido(hecho.id(), "Declaración " + declaracion.id()),
                        () -> hechos.anotarDescartado(hecho.id(), "Ya estaba declarado o el catálogo ya no lo exige."));
    }

    /** Paso 2: de la declaración al destinatario. Aquí sí se sale a la red. */
    void enviarLasAbiertas() {
        if (propiedades.destino() == null || propiedades.destino().isBlank()) {
            if (!avisadoDeQueNoHayDestino) {
                LOG.warn(
                        "No hay destinatario de declaraciones EDO configurado (`hispalis.edo.destino`), así que las "
                                + "obligaciones quedan registradas y NO salen. Se avisa una vez.");
                avisadoDeQueNoHayDestino = true;
            }
            return;
        }
        avisadoDeQueNoHayDestino = false;

        for (NotificacionEdo declaracion : declaraciones.abiertas(propiedades.intentos(), propiedades.tanda())) {
            enviar.ejecutar(declaracion);
        }
    }
}
