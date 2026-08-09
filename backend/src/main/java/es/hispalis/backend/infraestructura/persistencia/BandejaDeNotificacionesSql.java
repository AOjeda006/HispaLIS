package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.fhir.notificacion.BandejaDeNotificaciones;
import es.hispalis.backend.fhir.notificacion.EventoDeNotificacion;
import es.hispalis.backend.fhir.notificacion.EventoDeNotificacion.EstadoDeLaEntrega;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** La bandeja de notificaciones en PostgreSQL. Ver {@link RepositorioDePacientesSql} para el porqué del SQL. */
@Repository
public class BandejaDeNotificacionesSql implements BandejaDeNotificaciones {

    private static final String COLUMNAS =
            "id, suscripcion_id, numero, foco, ocurrido_en, estado, intentos, " + "ultimo_error";

    /**
     * El número se calcula dentro del propio {@code INSERT}, contra la tabla y no en memoria.
     *
     * <p>Hacerlo con un {@code SELECT max(...)} antes y un {@code INSERT} después dejaría una ventana
     * entre los dos en la que dos validaciones simultáneas del mismo paciente sacan el mismo número,
     * y entonces el receptor cree que se ha perdido un evento que sí llegó. La restricción
     * {@code UNIQUE (suscripcion_id, numero)} lo remata: si la carrera ocurre igual, la transacción
     * falla en vez de mentir.
     */
    private static final String ANOTAR =
            """
            INSERT INTO notificacion.evento (id, suscripcion_id, numero, foco, ocurrido_en, estado)
            SELECT :id, :suscripcionId,
                   coalesce(max(numero), 0) + 1,
                   :foco, :ocurridoEn, 'PENDIENTE'
              FROM notificacion.evento
             WHERE suscripcion_id = :suscripcionId
            RETURNING numero
            """;

    private static final String PENDIENTES = "SELECT " + COLUMNAS
            + """
             FROM notificacion.evento
            WHERE estado = 'PENDIENTE'
            ORDER BY ocurrido_en, numero
            LIMIT :tanda
            """;

    private static final String DE_LA_SUSCRIPCION = "SELECT " + COLUMNAS
            + """
             FROM notificacion.evento
            WHERE suscripcion_id = :suscripcionId
              AND numero BETWEEN :desde AND :hasta
            ORDER BY numero
            """;

    private static final String FALLIDOS = "SELECT " + COLUMNAS
            + """
             FROM notificacion.evento
            WHERE suscripcion_id = :suscripcionId
              AND estado = 'FALLIDO'
            ORDER BY numero DESC
            """;

    private static final String CUANTOS =
            "SELECT count(*) FROM notificacion.evento WHERE suscripcion_id = :suscripcionId";

    private static final String ENTREGADO =
            """
            UPDATE notificacion.evento
               SET estado = 'ENTREGADO', entregado_en = :cuando, intentos = intentos + 1, ultimo_error = NULL
             WHERE id = :id
            """;

    private static final String FALLO =
            """
            UPDATE notificacion.evento
               SET estado = :estado, intentos = intentos + 1, ultimo_error = :motivo
             WHERE id = :id
            """;

    private static final RowMapper<EventoDeNotificacion> FILA_A_EVENTO = BandejaDeNotificacionesSql::aEvento;

    private final NamedParameterJdbcTemplate jdbc;

    public BandejaDeNotificacionesSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EventoDeNotificacion anotar(String suscripcionId, String foco, Instant ocurridoEn) {
        UUID id = UUID.randomUUID();
        Long numero = jdbc.queryForObject(
                ANOTAR,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("suscripcionId", suscripcionId)
                        .addValue("foco", foco)
                        .addValue("ocurridoEn", Timestamp.from(ocurridoEn)),
                Long.class);
        return new EventoDeNotificacion(
                id,
                suscripcionId,
                numero == null ? 1L : numero,
                foco,
                ocurridoEn,
                EstadoDeLaEntrega.PENDIENTE,
                0,
                null);
    }

    @Override
    public List<EventoDeNotificacion> pendientes(int tanda) {
        return jdbc.query(PENDIENTES, new MapSqlParameterSource("tanda", tanda), FILA_A_EVENTO);
    }

    @Override
    public List<EventoDeNotificacion> deLaSuscripcion(String suscripcionId, long desde, long hasta) {
        return jdbc.query(
                DE_LA_SUSCRIPCION,
                new MapSqlParameterSource()
                        .addValue("suscripcionId", suscripcionId)
                        .addValue("desde", desde)
                        .addValue("hasta", hasta),
                FILA_A_EVENTO);
    }

    @Override
    public long eventosDe(String suscripcionId) {
        Long cuantos =
                jdbc.queryForObject(CUANTOS, new MapSqlParameterSource("suscripcionId", suscripcionId), Long.class);
        return cuantos == null ? 0L : cuantos;
    }

    @Override
    public List<EventoDeNotificacion> fallidosDe(String suscripcionId) {
        return jdbc.query(FALLIDOS, new MapSqlParameterSource("suscripcionId", suscripcionId), FILA_A_EVENTO);
    }

    @Override
    public void marcarEntregado(UUID id, Instant cuando) {
        jdbc.update(
                ENTREGADO, new MapSqlParameterSource().addValue("id", id).addValue("cuando", Timestamp.from(cuando)));
    }

    @Override
    public void marcarIntentoFallido(UUID id, String motivo, boolean definitivo) {
        jdbc.update(
                FALLO,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("estado", definitivo ? "FALLIDO" : "PENDIENTE")
                        .addValue("motivo", motivo));
    }

    private static EventoDeNotificacion aEvento(ResultSet fila, int numeroDeFila) throws SQLException {
        return new EventoDeNotificacion(
                UUID.fromString(fila.getString("id")),
                fila.getString("suscripcion_id"),
                fila.getLong("numero"),
                fila.getString("foco"),
                fila.getTimestamp("ocurrido_en").toInstant(),
                EstadoDeLaEntrega.valueOf(fila.getString("estado")),
                fila.getInt("intentos"),
                fila.getString("ultimo_error"));
    }
}
