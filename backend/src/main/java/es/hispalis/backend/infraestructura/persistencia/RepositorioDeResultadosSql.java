package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistencia del agregado {@link Resultado}. Ver {@link RepositorioDePacientesSql} para el porqué del SQL. */
@Repository
public class RepositorioDeResultadosSql implements RepositorioDeResultados {

    private static final String INSERTAR =
            """
            INSERT INTO dominio.resultado (
                id, especimen_id, paciente_id, codigo_de_prueba, valor, unidad_ucum, valor_textual)
            VALUES (
                :id, :especimenId, :pacienteId, :codigoDePrueba, :valor, :unidadUcum, :valorTextual)
            """;

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
                        .addValue("codigoDePrueba", resultado.codigoDePrueba())
                        .addValue("valor", resultado.valor().orElse(null))
                        .addValue("unidadUcum", resultado.unidadUcum().orElse(null))
                        .addValue("valorTextual", resultado.valorTextual().orElse(null)));
    }
}
