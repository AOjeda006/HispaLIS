package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.edo.EstadoDeDeclaracion;
import es.hispalis.backend.dominio.edo.ModalidadDeDeclaracion;
import es.hispalis.backend.dominio.edo.NotificacionEdo;
import es.hispalis.backend.dominio.edo.NotificacionEdo.Acuse;
import es.hispalis.backend.dominio.edo.RepositorioDeNotificacionesEdo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistencia de las declaraciones a Salud Pública. Ver {@link RepositorioDePacientesSql} para el
 * porqué del SQL.
 *
 * <p>El {@code CHECK} de la V15 —{@code ACUSADA} exige acuse— es la misma regla que el agregado, dicha
 * en la base de datos. No es redundancia por gusto: el agregado protege el único camino que hay hoy,
 * y el {@code CHECK} protege también el {@code UPDATE} que alguien escriba en una consola dentro de
 * dos años.
 */
@Repository
public class RepositorioDeNotificacionesEdoSql implements RepositorioDeNotificacionesEdo {

    private static final String COLUMNAS =
            """
            id, resultado_id, paciente_id, declarante, codigo_enfermedad, nombre_enfermedad, modalidad,
            abierta_en, vencimiento, estado, intentos, ultimo_error,
            acuse_sistema, acuse_numero, acuse_recibido_en
            """;

    private static final String INSERTAR =
            """
            INSERT INTO dominio.notificacion_edo (
                id, resultado_id, paciente_id, declarante, codigo_enfermedad, nombre_enfermedad,
                modalidad, abierta_en, vencimiento, estado, intentos, ultimo_error)
            VALUES (
                :id, :resultadoId, :pacienteId, :declarante, :codigoEnfermedad, :nombreEnfermedad, :modalidad,
                :abiertaEn, :vencimiento, :estado, :intentos, :ultimoError)
            """;

    /**
     * Solo se mueve lo que cambia con el ciclo de vida. Ni el plazo ni la enfermedad se tocan al
     * actualizar: el vencimiento se congela al abrir y un {@code UPDATE} que lo recalculase borraría
     * la ventana que de verdad estuvo vigente.
     */
    private static final String ACTUALIZAR =
            """
            UPDATE dominio.notificacion_edo
               SET estado = :estado,
                   intentos = :intentos,
                   ultimo_error = :ultimoError,
                   acuse_sistema = :acuseSistema,
                   acuse_numero = :acuseNumero,
                   acuse_recibido_en = :acuseRecibidoEn
             WHERE id = :id
            """;

    private static final String POR_ID = "SELECT " + COLUMNAS + " FROM dominio.notificacion_edo WHERE id = :id";

    private static final String POR_RESULTADO =
            "SELECT " + COLUMNAS + " FROM dominio.notificacion_edo WHERE resultado_id = :resultadoId";

    /** Por vencimiento: lo que antes se pasa de plazo, primero. Es el orden que usa el índice parcial. */
    private static final String ABIERTAS =
            """
            SELECT %s
              FROM dominio.notificacion_edo
             WHERE estado IN ('PENDIENTE', 'ENVIADA')
               AND intentos < :intentosMaximos
             ORDER BY vencimiento, abierta_en
             LIMIT :tanda
            """
                    .formatted(COLUMNAS);

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDeNotificacionesEdoSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(NotificacionEdo declaracion) {
        jdbc.update(
                INSERTAR,
                new MapSqlParameterSource()
                        .addValue("id", declaracion.id())
                        .addValue("resultadoId", declaracion.resultadoId())
                        .addValue("pacienteId", declaracion.pacienteId())
                        .addValue("declarante", declaracion.declarante().orElse(null))
                        .addValue("codigoEnfermedad", declaracion.codigoDeEnfermedad())
                        .addValue("nombreEnfermedad", declaracion.nombreDeLaEnfermedad())
                        .addValue("modalidad", declaracion.modalidad().name())
                        .addValue("abiertaEn", Timestamp.from(declaracion.abiertaEn()))
                        .addValue("vencimiento", Timestamp.from(declaracion.vencimiento()))
                        .addValue("estado", declaracion.estado().name())
                        .addValue("intentos", declaracion.intentos())
                        .addValue("ultimoError", declaracion.ultimoError().orElse(null)));
    }

    @Override
    public void actualizar(NotificacionEdo declaracion) {
        Optional<Acuse> acuse = declaracion.acuse();
        jdbc.update(
                ACTUALIZAR,
                new MapSqlParameterSource()
                        .addValue("id", declaracion.id())
                        .addValue("estado", declaracion.estado().name())
                        .addValue("intentos", declaracion.intentos())
                        .addValue("ultimoError", declaracion.ultimoError().orElse(null))
                        .addValue("acuseSistema", acuse.map(Acuse::sistema).orElse(null))
                        .addValue("acuseNumero", acuse.map(Acuse::numero).orElse(null))
                        .addValue(
                                "acuseRecibidoEn",
                                acuse.map(recibo -> Timestamp.from(recibo.recibidoEn()))
                                        .orElse(null)));
    }

    @Override
    public Optional<NotificacionEdo> buscarPorId(UUID id) {
        return jdbc
                .query(POR_ID, new MapSqlParameterSource("id", id), RepositorioDeNotificacionesEdoSql::rehidratar)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<NotificacionEdo> buscarPorResultado(UUID resultadoId) {
        return jdbc
                .query(
                        POR_RESULTADO,
                        new MapSqlParameterSource("resultadoId", resultadoId),
                        RepositorioDeNotificacionesEdoSql::rehidratar)
                .stream()
                .findFirst();
    }

    @Override
    public List<NotificacionEdo> abiertas(int intentosMaximos, int tanda) {
        return jdbc.query(
                ABIERTAS,
                new MapSqlParameterSource()
                        .addValue("intentosMaximos", intentosMaximos)
                        .addValue("tanda", tanda),
                RepositorioDeNotificacionesEdoSql::rehidratar);
    }

    private static NotificacionEdo rehidratar(ResultSet fila, int numero) throws SQLException {
        String numeroDeAcuse = fila.getString("acuse_numero");
        return NotificacionEdo.rehidratar(
                fila.getObject("id", UUID.class),
                fila.getObject("resultado_id", UUID.class),
                fila.getObject("paciente_id", UUID.class),
                fila.getString("declarante"),
                fila.getString("codigo_enfermedad"),
                fila.getString("nombre_enfermedad"),
                ModalidadDeDeclaracion.valueOf(fila.getString("modalidad")),
                instante(fila, "abierta_en"),
                instante(fila, "vencimiento"),
                EstadoDeDeclaracion.valueOf(fila.getString("estado")),
                fila.getInt("intentos"),
                fila.getString("ultimo_error"),
                numeroDeAcuse == null
                        ? null
                        : new Acuse(
                                fila.getString("acuse_sistema"), numeroDeAcuse, instante(fila, "acuse_recibido_en")));
    }

    private static Instant instante(ResultSet fila, String columna) throws SQLException {
        Timestamp marca = fila.getTimestamp(columna);
        return marca == null ? null : marca.toInstant();
    }
}
