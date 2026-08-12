package es.hispalis.backend.aplicacion.edo;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import es.hispalis.backend.dominio.edo.NotificacionEdo;
import es.hispalis.backend.dominio.edo.RepositorioDeNotificacionesEdo;
import es.hispalis.backend.dominio.edo.SaludPublica;
import es.hispalis.backend.dominio.edo.SaludPublica.Respuesta;
import es.hispalis.backend.fhir.edo.TraductorDeNotificacionEdo;
import java.time.Instant;
import org.hl7.fhir.r5.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manda una declaración a Salud Pública y anota lo que conteste.
 *
 * <h2>La llamada va FUERA de la transacción</h2>
 *
 * <p>Primero se habla con el destinatario y después se escribe lo que dijo. Al revés —una llamada HTTP
 * a un tercero dentro de una transacción— la mantendría abierta lo que tarde en contestar alguien que
 * no controlamos, y con el destinatario colgado eso son conexiones de base de datos retenidas por una
 * administración que no responde. Es la misma decisión que tomó el relay de notificaciones del ítem
 * 44, y la consecuencia es la misma: si el proceso se cae entre la respuesta y el {@code UPDATE}, la
 * declaración se reintenta y el destinatario recibe un duplicado. Se prefiere eso a lo otro, y se
 * declara.
 *
 * <h2>Las cuatro respuestas se distinguen</h2>
 *
 * <p>Acusada, recibida-sin-registro, rechazada y no-llegó. Fundir las dos del medio dejaría al
 * laboratorio sin poder decir si Salud Pública no contesta o si contestó que no, que es justo lo que
 * hay que saber para decidir a quién se llama por teléfono.
 */
public class EnviarNotificacionEdo {

    private static final Logger LOG = LoggerFactory.getLogger(EnviarNotificacionEdo.class);

    private final RepositorioDeNotificacionesEdo declaraciones;
    private final SaludPublica saludPublica;
    private final TraductorDeNotificacionEdo traductor;
    private final DestinatarioDeLaDeclaracion destinatario;
    private final DaoRegistry daos;

    public EnviarNotificacionEdo(
            RepositorioDeNotificacionesEdo declaraciones,
            SaludPublica saludPublica,
            TraductorDeNotificacionEdo traductor,
            DestinatarioDeLaDeclaracion destinatario,
            DaoRegistry daos) {
        this.declaraciones = declaraciones;
        this.saludPublica = saludPublica;
        this.traductor = traductor;
        this.destinatario = destinatario;
        this.daos = daos;
    }

    /**
     * @param declaracion una declaración abierta
     * @return cómo quedó después de intentarlo
     */
    public NotificacionEdo ejecutar(NotificacionEdo declaracion) {
        Respuesta respuesta = saludPublica.declarar(declaracion);
        NotificacionEdo despues = aplicar(declaracion, respuesta);
        anotar(despues);
        avisar(declaracion, despues, respuesta);
        return despues;
    }

    /**
     * Traduce la respuesta a un movimiento del agregado.
     *
     * <p>El {@code switch} sobre la interfaz sellada <strong>no lleva {@code default}</strong>: el día
     * que aparezca una quinta respuesta, esto deja de compilar y alguien tiene que decidir qué se hace
     * con ella. Con un {@code default}, una respuesta nueva se trataría como la más parecida sin que
     * nadie lo pensara.
     */
    private static NotificacionEdo aplicar(NotificacionEdo declaracion, Respuesta respuesta) {
        return switch (respuesta) {
            case Respuesta.Acusada acusada -> declaracion.acusar(acusada.acuse());
            case Respuesta.RecibidaSinRegistro sinRegistro -> declaracion.marcarEnviadaSinAcuse(sinRegistro.detalle());
            case Respuesta.Rechazada rechazada -> declaracion.rechazar(rechazada.motivo());
            case Respuesta.NoLlego noLlego -> declaracion.anotarIntentoFallido(noLlego.motivo());
        };
    }

    /**
     * Guarda el agregado y reescribe el {@code Task}, en una transacción.
     *
     * <p>Las dos escrituras van juntas por lo mismo de siempre (§9): una declaración acusada en el
     * dominio y un {@code Task} que sigue diciendo «pendiente» sería una pantalla mintiendo sobre una
     * obligación legal.
     */
    @Transactional
    void anotar(NotificacionEdo declaracion) {
        declaraciones.actualizar(declaracion);
        Task tarea = traductor.aFhir(declaracion, destinatario.organismo());
        daos.getResourceDao(Task.class).update(tarea, new SystemRequestDetails());
    }

    /** Se traza la enfermedad y el estado; el caso, nunca. Ver {@code DetectarDeclaracionObligatoria}. */
    private static void avisar(NotificacionEdo antes, NotificacionEdo despues, Respuesta respuesta) {
        switch (respuesta) {
            case Respuesta.Acusada acusada ->
                LOG.info(
                        "Declaración de {} acusada por Salud Pública con el registro {}.",
                        despues.codigoDeEnfermedad(),
                        acusada.acuse().numero());
            case Respuesta.RecibidaSinRegistro sinRegistro ->
                LOG.warn(
                        "Salud Pública ha recibido la declaración de {} y no ha devuelto número de registro, así que "
                                + "NO consta declarada. Vence {}. Detalle: {}",
                        despues.codigoDeEnfermedad(),
                        despues.vencimiento(),
                        sinRegistro.detalle());
            case Respuesta.Rechazada rechazada ->
                LOG.error(
                        "Salud Pública ha RECHAZADO la declaración de {}: {}. No se reintenta sola.",
                        despues.codigoDeEnfermedad(),
                        rechazada.motivo());
            case Respuesta.NoLlego noLlego ->
                LOG.warn(
                        "No se ha podido entregar la declaración de {} (intento {}). Vence {}. Causa: {}",
                        despues.codigoDeEnfermedad(),
                        despues.intentos(),
                        despues.vencimiento(),
                        noLlego.motivo());
        }
        if (despues.estaFueraDePlazo(Instant.now()) && antes.sigueAbierta()) {
            LOG.error(
                    "La declaración de {} ha pasado su plazo legal ({}) sin acuse. Requiere gestión manual.",
                    despues.codigoDeEnfermedad(),
                    despues.vencimiento());
        }
    }
}
