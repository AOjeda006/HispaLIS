package es.hispalis.backend.aplicacion.edo;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import es.hispalis.backend.aplicacion.exportacion.ApuntarEnLaCohorte;
import es.hispalis.backend.dominio.edo.CatalogoEdo;
import es.hispalis.backend.dominio.edo.NotificacionEdo;
import es.hispalis.backend.dominio.edo.ReglaDeDeclaracion;
import es.hispalis.backend.dominio.edo.RepositorioDeNotificacionesEdo;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.edo.TraductorDeNotificacionEdo;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r5.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Convierte la obligación apuntada en una declaración que se puede seguir.
 *
 * <p>Lo llama el notificador cuando ve pasar un {@code RESULTADO_DECLARABLE} por el {@code outbox},
 * <strong>nunca la validación</strong>. Es la diferencia entre un sistema donde el laboratorio deja de
 * firmar resultados porque una administración no contesta y uno donde no.
 *
 * <p><strong>Abrir no es enviar.</strong> Aquí solo nace la tarea, con su plazo y en
 * {@code PENDIENTE}. Enviarla es {@link EnviarNotificacionEdo}, y separarlo es lo que permite que la
 * obligación quede registrada aunque el destinatario esté caído desde antes.
 *
 * <p>Es idempotente: la entrega del {@code outbox} es <strong>al menos una vez</strong>, así que el
 * mismo hecho puede llegar dos veces. Dos declaraciones del mismo caso inflarían el recuento de
 * Salud Pública, y en vigilancia epidemiológica un número de más dispara una investigación que no
 * toca. Lo garantizan esta comprobación y el {@code UNIQUE} de la V15, que es la que de verdad cierra
 * la puerta si dos procesos entran a la vez.
 */
public class AbrirNotificacionEdo {

    private static final Logger LOG = LoggerFactory.getLogger(AbrirNotificacionEdo.class);

    private final RepositorioDeResultados resultados;
    private final RepositorioDeNotificacionesEdo declaraciones;
    private final CatalogoEdo catalogo;
    private final TraductorDeNotificacionEdo traductor;
    private final DestinatarioDeLaDeclaracion destinatario;
    private final ApuntarEnLaCohorte cohorte;
    private final DaoRegistry daos;

    public AbrirNotificacionEdo(
            RepositorioDeResultados resultados,
            RepositorioDeNotificacionesEdo declaraciones,
            CatalogoEdo catalogo,
            TraductorDeNotificacionEdo traductor,
            DestinatarioDeLaDeclaracion destinatario,
            ApuntarEnLaCohorte cohorte,
            DaoRegistry daos) {
        this.resultados = resultados;
        this.declaraciones = declaraciones;
        this.catalogo = catalogo;
        this.traductor = traductor;
        this.destinatario = destinatario;
        this.cohorte = cohorte;
        this.daos = daos;
    }

    /**
     * @param resultadoId el resultado validado que el hecho señala
     * @param cuandoSeValido el momento del hecho, que es cuando nació la obligación. <strong>No
     *     «ahora»:</strong> si el notificador arrastra una cola de dos horas, el plazo legal no se
     *     estira dos horas.
     * @return la declaración recién abierta, o vacío si el resultado ya tenía una o si el catálogo
     *     dice que este resultado no obliga a nada
     */
    @Transactional
    public Optional<NotificacionEdo> ejecutar(UUID resultadoId, Instant cuandoSeValido) {
        Optional<NotificacionEdo> yaAbierta = declaraciones.buscarPorResultado(resultadoId);
        if (yaAbierta.isPresent()) {
            LOG.debug("El resultado {} ya tenía declaración abierta; el hecho venía repetido.", resultadoId);
            return Optional.empty();
        }

        Optional<Resultado> resultado = resultados.buscarPorId(resultadoId);
        if (resultado.isEmpty()) {
            LOG.warn(
                    "El hecho apunta al resultado {}, que no está en el laboratorio. No se abre declaración.",
                    resultadoId);
            return Optional.empty();
        }

        // Se vuelve a preguntar al catálogo en vez de fiarse del hecho, y es a propósito: el hecho no
        // lleva la enfermedad dentro (invariante 6), así que aquí no hay nada que copiar. La decisión
        // la toma el agregado con el catálogo delante, igual que la tomó al validar.
        Optional<ReglaDeDeclaracion> regla = resultado.get().obligaADeclarar(catalogo);
        if (regla.isEmpty()) {
            LOG.warn(
                    "El hecho decía que el resultado {} era declarable y el catálogo ya no lo dice. No se abre "
                            + "declaración: quien mantiene el catálogo debería mirar si el cambio era intencionado.",
                    resultadoId);
            return Optional.empty();
        }

        NotificacionEdo declaracion = NotificacionEdo.abrir(
                resultadoId,
                resultado.get().pacienteId(),
                resultado.get().medicion().realizadaPor().orElse(null),
                regla.get(),
                cuandoSeValido);
        declaraciones.guardar(declaracion);
        proyectar(declaracion);
        // Y el caso entra en la cohorte de su enfermedad, aquí y no en otro sitio: si la declaración
        // existe, la cohorte la cuenta. Cualquier hueco entre las dos cosas es el momento en el que
        // alguien investigando un brote pediría la cohorte y le faltaría un caso.
        cohorte.ejecutar(declaracion);

        // Se traza la enfermedad y el plazo, nunca el caso: un log que atase «legionelosis» a un
        // paciente concreto sería historia clínica en un fichero sin consentimiento.
        LOG.info(
                "Declaración obligatoria abierta: {}, modalidad {}, vence {}.",
                declaracion.codigoDeEnfermedad(),
                declaracion.modalidad(),
                declaracion.vencimiento());
        return Optional.of(declaracion);
    }

    /**
     * Escribe el {@code Task} en la misma transacción que el agregado (§9).
     *
     * <p>Read-your-writes vale también aquí: en cuanto el notificador dice que la declaración existe,
     * un {@code GET /fhir/Task?focus=…} la encuentra. Si la proyección fuese asíncrona, la pantalla de
     * declaraciones pendientes enseñaría menos de las que hay, que en esto es lo peor que puede pasar.
     */
    void proyectar(NotificacionEdo declaracion) {
        Task tarea = traductor.aFhir(declaracion, destinatario.organismo());
        daos.getResourceDao(Task.class).update(tarea, new SystemRequestDetails());
    }
}
