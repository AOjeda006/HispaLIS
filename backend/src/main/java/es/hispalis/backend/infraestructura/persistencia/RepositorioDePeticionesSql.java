package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistencia del agregado {@link Peticion}. Ver {@link RepositorioDePacientesSql} para el porqué del SQL. */
@Repository
public class RepositorioDePeticionesSql implements RepositorioDePeticiones {

    private static final String INSERTAR =
            """
            INSERT INTO dominio.peticion (
                id, numero_de_peticion, paciente_id, codigo_de_prueba, solicitante, solicitada_en)
            VALUES (:id, :numero, :pacienteId, :codigoDePrueba, :solicitante, :solicitadaEn)
            """;

    private static final String BUSCAR_POR_ID =
            """
            SELECT id, numero_de_peticion, paciente_id, codigo_de_prueba, solicitante, solicitada_en
              FROM dominio.peticion
             WHERE id = :id
            """;

    private static final RowMapper<Peticion> FILA_A_PETICION = RepositorioDePeticionesSql::aPeticion;

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDePeticionesSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(Peticion peticion) {
        jdbc.update(
                INSERTAR,
                new MapSqlParameterSource()
                        .addValue("id", peticion.id())
                        .addValue("numero", peticion.numeroDePeticion())
                        .addValue("pacienteId", peticion.pacienteId())
                        .addValue("codigoDePrueba", peticion.codigoDePrueba())
                        .addValue("solicitante", peticion.solicitante())
                        .addValue("solicitadaEn", Timestamp.from(peticion.solicitadaEn())));
    }

    @Override
    public Optional<Peticion> buscarPorId(UUID id) {
        return jdbc.query(BUSCAR_POR_ID, new MapSqlParameterSource("id", id), FILA_A_PETICION).stream()
                .findFirst();
    }

    private static Peticion aPeticion(ResultSet fila, int numeroDeFila) throws SQLException {
        return Peticion.reconstruir(
                UUID.fromString(fila.getString("id")),
                fila.getString("numero_de_peticion"),
                UUID.fromString(fila.getString("paciente_id")),
                fila.getString("codigo_de_prueba"),
                fila.getString("solicitante"),
                fila.getTimestamp("solicitada_en").toInstant());
    }
}
