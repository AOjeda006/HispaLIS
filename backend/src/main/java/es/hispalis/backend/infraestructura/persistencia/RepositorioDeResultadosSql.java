package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistencia del agregado {@link Resultado}. Ver {@link RepositorioDePacientesSql} para el porqué del SQL. */
@Repository
public class RepositorioDeResultadosSql implements RepositorioDeResultados {

    private static final String INSERTAR =
            """
            INSERT INTO dominio.resultado (
                id, especimen_id, paciente_id, peticion_id, codigo_de_prueba, valor, unidad_ucum, valor_textual)
            VALUES (
                :id, :especimenId, :pacienteId, :peticionId, :codigoDePrueba, :valor, :unidadUcum, :valorTextual)
            """;

    private static final String BUSCAR_POR_ID =
            """
            SELECT id, especimen_id, paciente_id, peticion_id, codigo_de_prueba, valor, unidad_ucum, valor_textual
              FROM dominio.resultado
             WHERE id = :id
            """;

    private static final RowMapper<Resultado> FILA_A_RESULTADO = RepositorioDeResultadosSql::aResultado;

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDeResultadosSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(Resultado resultado) {
        jdbc.update(
                INSERTAR,
                new MapSqlParameterSource()
                        .addValue("id", resultado.id())
                        .addValue("especimenId", resultado.especimenId())
                        .addValue("pacienteId", resultado.pacienteId())
                        .addValue("peticionId", resultado.peticionId().orElse(null))
                        .addValue("codigoDePrueba", resultado.codigoDePrueba())
                        .addValue("valor", resultado.valor().orElse(null))
                        .addValue("unidadUcum", resultado.unidadUcum().orElse(null))
                        .addValue("valorTextual", resultado.valorTextual().orElse(null)));
    }

    @Override
    public Optional<Resultado> buscarPorId(UUID id) {
        return jdbc.query(BUSCAR_POR_ID, new MapSqlParameterSource("id", id), FILA_A_RESULTADO).stream()
                .findFirst();
    }

    private static Resultado aResultado(ResultSet fila, int numeroDeFila) throws SQLException {
        String peticionId = fila.getString("peticion_id");
        return Resultado.reconstruir(
                UUID.fromString(fila.getString("id")),
                UUID.fromString(fila.getString("especimen_id")),
                UUID.fromString(fila.getString("paciente_id")),
                peticionId == null ? null : UUID.fromString(peticionId),
                fila.getString("codigo_de_prueba"),
                fila.getBigDecimal("valor"),
                fila.getString("unidad_ucum"),
                fila.getString("valor_textual"));
    }
}
