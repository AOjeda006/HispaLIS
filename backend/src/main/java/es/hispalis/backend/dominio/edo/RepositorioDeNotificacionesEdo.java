package es.hispalis.backend.dominio.edo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dónde viven las declaraciones a Salud Pública. */
public interface RepositorioDeNotificacionesEdo {

    void guardar(NotificacionEdo declaracion);

    void actualizar(NotificacionEdo declaracion);

    Optional<NotificacionEdo> buscarPorId(UUID id);

    /**
     * Si un resultado ya tiene su declaración abierta.
     *
     * <p>Hace falta porque la entrega del {@code outbox} es <strong>al menos una vez</strong>: el mismo
     * hecho puede llegar dos veces y sin esto habría dos declaraciones del mismo caso. Salud Pública
     * recibiría el caso duplicado, que en vigilancia epidemiológica no es un error inocuo — un
     * recuento inflado dispara investigaciones que no tocan.
     */
    Optional<NotificacionEdo> buscarPorResultado(UUID resultadoId);

    /**
     * Las que el notificador todavía tiene que intentar, de la más antigua a la más reciente.
     *
     * @param intentosMaximos por encima de esto se deja de intentar sola; volver a intentarlo es una
     *     decisión de operación, igual que reprocesar desde la bandeja de errores del motor
     */
    List<NotificacionEdo> abiertas(int intentosMaximos, int tanda);
}
