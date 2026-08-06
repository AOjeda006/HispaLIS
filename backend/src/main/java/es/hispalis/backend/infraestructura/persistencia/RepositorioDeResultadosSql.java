package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.resultado.Medicion;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.dominio.resultado.Validacion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
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
                id, especimen_id, paciente_id, peticion_id, codigo_de_prueba, valor, unidad_ucum, valor_textual,
                medido_en, realizado_por)
            VALUES (
                :id, :especimenId, :pacienteId, :peticionId, :codigoDePrueba, :valor, :unidadUcum, :valorTextual,
                :medidoEn, :realizadoPor)
            """;

    private static final String VALIDAR =
            """
            UPDATE dominio.resultado
               SET validado_por = :validadoPor, validado_en = :validadoEn
             WHERE id = :id
            """;

    private static final String BUSCAR_POR_ID =
            """
            SELECT id, especimen_id, paciente_id, peticion_id, codigo_de_prueba, valor, unidad_ucum, valor_textual,
                   medido_en, realizado_por, validado_por, validado_en
              FROM dominio.resultado
             WHERE id = :id
            """;

    private static final String LINEAS_CON_RESULTADO =
            """
            SELECT DISTINCT peticion_id
              FROM dominio.resultado
             WHERE peticion_id IN (:lineas)
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
                        .addValue("valorTextual", resultado.valorTextual().orElse(null))
                        .addValue(
                                "medidoEn",
                                resultado
                                        .medicion()
                                        .realizadaEn()
                                        .map(Timestamp::from)
                                        .orElse(null))
                        .addValue(
                                "realizadoPor",
                                resultado.medicion().realizadaPor().orElse(null)));
    }

    @Override
    public void actualizar(Resultado resultado) {
        // Solo se escriben las dos columnas de la firma. El valor medido y su unidad no cambian al
        // validar: validar es responder de una cifra, no reescribirla. Un `UPDATE` que las tocase
        // convertiría una firma en una corrección silenciosa.
        jdbc.update(
                VALIDAR,
                new MapSqlParameterSource()
                        .addValue("id", resultado.id())
                        .addValue(
                                "validadoPor",
                                resultado
                                        .validacion()
                                        .map(Validacion::facultativo)
                                        .orElse(null))
                        .addValue(
                                "validadoEn",
                                resultado
                                        .validacion()
                                        .map(Validacion::realizadaEn)
                                        .map(Timestamp::from)
                                        .orElse(null)));
    }

    @Override
    public Optional<Resultado> buscarPorId(UUID id) {
        return jdbc.query(BUSCAR_POR_ID, new MapSqlParameterSource("id", id), FILA_A_RESULTADO).stream()
                .findFirst();
    }

    @Override
    public Set<UUID> lineasConResultado(Collection<UUID> lineasDePeticion) {
        // `IN ()` no es SQL válido: un volante vacío se atajaba aquí o reventaba en el driver.
        if (lineasDePeticion.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.queryForList(
                LINEAS_CON_RESULTADO, new MapSqlParameterSource("lineas", lineasDePeticion), UUID.class));
    }

    private static Resultado aResultado(ResultSet fila, int numeroDeFila) throws SQLException {
        String peticionId = fila.getString("peticion_id");
        Timestamp medidoEn = fila.getTimestamp("medido_en");
        String validadoPor = fila.getString("validado_por");
        Timestamp validadoEn = fila.getTimestamp("validado_en");
        return Resultado.reconstruir(
                UUID.fromString(fila.getString("id")),
                UUID.fromString(fila.getString("especimen_id")),
                UUID.fromString(fila.getString("paciente_id")),
                peticionId == null ? null : UUID.fromString(peticionId),
                fila.getString("codigo_de_prueba"),
                fila.getBigDecimal("valor"),
                fila.getString("unidad_ucum"),
                fila.getString("valor_textual"),
                Medicion.de(medidoEn == null ? null : medidoEn.toInstant(), fila.getString("realizado_por")),
                validadoPor == null ? null : Validacion.por(validadoPor, validadoEn.toInstant()));
    }
}
