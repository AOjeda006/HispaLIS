package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.informe.Informe;
import es.hispalis.backend.dominio.informe.RepositorioDeInformes;
import java.sql.Timestamp;
import java.util.List;
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
}
