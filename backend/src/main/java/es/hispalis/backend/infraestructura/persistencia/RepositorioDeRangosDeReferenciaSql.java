package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.resultado.RangoDeReferencia;
import es.hispalis.backend.dominio.resultado.RepositorioDeRangosDeReferencia;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lectura de los rangos de referencia del laboratorio.
 *
 * <p>Se consultan al proyectar cada resultado. Es una lectura por resultado y son diecinueve filas en
 * total, así que la caché queda para cuando el perfil diga que hace falta y no antes: cachear datos
 * de configuración editables sin invalidación es la forma habitual de publicar durante horas un rango
 * que ya se corrigió.
 */
@Repository
public class RepositorioDeRangosDeReferenciaSql implements RepositorioDeRangosDeReferencia {

    private static final String BUSCAR_POR_PRUEBA =
            """
            SELECT codigo_de_prueba, sexo, bajo, alto, unidad_ucum
              FROM dominio.rango_de_referencia
             WHERE codigo_de_prueba = :codigoDePrueba
             ORDER BY sexo NULLS FIRST
            """;

    private static final RowMapper<RangoDeReferencia> FILA_A_RANGO = RepositorioDeRangosDeReferenciaSql::aRango;

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDeRangosDeReferenciaSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RangoDeReferencia> buscarPorPrueba(String codigoDePrueba) {
        return jdbc.query(BUSCAR_POR_PRUEBA, new MapSqlParameterSource("codigoDePrueba", codigoDePrueba), FILA_A_RANGO);
    }

    private static RangoDeReferencia aRango(ResultSet fila, int numeroDeFila) throws SQLException {
        return new RangoDeReferencia(
                fila.getString("codigo_de_prueba"),
                fila.getString("sexo"),
                fila.getBigDecimal("bajo"),
                fila.getBigDecimal("alto"),
                fila.getString("unidad_ucum"));
    }
}
