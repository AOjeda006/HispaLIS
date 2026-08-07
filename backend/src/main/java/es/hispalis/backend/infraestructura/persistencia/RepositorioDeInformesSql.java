package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.informe.Informe;
import es.hispalis.backend.dominio.informe.RepositorioDeInformes;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/** Persistencia del agregado {@link Informe}. Ver {@link RepositorioDePacientesSql} para el porqué del SQL. */
@Repository
public class RepositorioDeInformesSql implements RepositorioDeInformes {

    private static final String INSERTAR_INFORME =
            """
            INSERT INTO dominio.informe (id, paciente_id, emisor, emitido_en)
            VALUES (:id, :pacienteId, :emisor, :emitidoEn)
            """;

    private static final String INSERTAR_LINEA =
            """
            INSERT INTO dominio.informe_resultado (informe_id, resultado_id)
            VALUES (:informeId, :resultadoId)
            """;

    /**
     * El informe con sus resultados en una sola consulta.
     *
     * <p>Se agregan las líneas con {@code array_agg} en vez de traer una fila por resultado y
     * agruparlas en Java: son parte del agregado y se cargan con él, y una consulta por informe
     * convertiría el recorrido del reconciliador en un {@code N+1} sobre el laboratorio entero.
     */
    private static final String BUSCAR_DE_PACIENTE =
            """
            SELECT i.id, i.paciente_id, i.emisor, i.emitido_en,
                   array_agg(r.resultado_id ORDER BY r.resultado_id) AS resultados
              FROM dominio.informe i
              JOIN dominio.informe_resultado r ON r.informe_id = i.id
             WHERE i.paciente_id = :pacienteId
             GROUP BY i.id, i.paciente_id, i.emisor, i.emitido_en
             ORDER BY i.emitido_en, i.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDeInformesSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(Informe informe) {
        jdbc.update(
                INSERTAR_INFORME,
                new MapSqlParameterSource()
                        .addValue("id", informe.id())
                        .addValue("pacienteId", informe.pacienteId())
                        .addValue("emisor", informe.emisor())
                        .addValue("emitidoEn", Timestamp.from(informe.emitidoEn())));

        // El agregado se guarda entero de una vez: sus líneas no tienen vida propia fuera de él.
        List<SqlParameterSource> lineas = informe.resultadoIds().stream()
                .map(resultadoId -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("informeId", informe.id())
                        .addValue("resultadoId", resultadoId))
                .toList();
        jdbc.batchUpdate(INSERTAR_LINEA, lineas.toArray(SqlParameterSource[]::new));
    }

    @Override
    public List<Informe> buscarDePaciente(UUID pacienteId) {
        RowMapper<Informe> filaAInforme = (fila, numeroDeFila) -> Informe.reconstruir(
                UUID.fromString(fila.getString("id")),
                UUID.fromString(fila.getString("paciente_id")),
                resultadosDe(fila.getArray("resultados")),
                fila.getString("emisor"),
                fila.getTimestamp("emitido_en").toInstant());
        return jdbc.query(BUSCAR_DE_PACIENTE, new MapSqlParameterSource("pacienteId", pacienteId), filaAInforme);
    }

    private static List<UUID> resultadosDe(Array columna) throws SQLException {
        return Arrays.stream((UUID[]) columna.getArray()).toList();
    }
}
