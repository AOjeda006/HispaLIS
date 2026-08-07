package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.ConflictoDeNegocio;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.EstadoDeEspecimen;
import es.hispalis.backend.dominio.especimen.NumeroDeAcceso;
import es.hispalis.backend.dominio.especimen.RepositorioDeEspecimenes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistencia del agregado {@link Especimen}. Ver {@link RepositorioDePacientesSql} para el porqué del SQL. */
@Repository
public class RepositorioDeEspecimenesSql implements RepositorioDeEspecimenes {

    private static final String INSERTAR =
            """
            INSERT INTO dominio.especimen (id, numero_de_acceso, paciente_id, tipo, estado, motivo_de_rechazo)
            VALUES (:id, :numeroDeAcceso, :pacienteId, :tipo, :estado, :motivoDeRechazo)
            """;

    private static final String BUSCAR_POR_ID =
            """
            SELECT id, numero_de_acceso, paciente_id, tipo, estado, motivo_de_rechazo
              FROM dominio.especimen
             WHERE id = :id
            """;

    private static final String BUSCAR_DE_PACIENTE =
            """
            SELECT id, numero_de_acceso, paciente_id, tipo, estado, motivo_de_rechazo
              FROM dominio.especimen
             WHERE paciente_id = :pacienteId
             ORDER BY numero_de_acceso
            """;

    private static final RowMapper<Especimen> FILA_A_ESPECIMEN = RepositorioDeEspecimenesSql::aEspecimen;

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDeEspecimenesSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(Especimen especimen) {
        try {
            jdbc.update(
                    INSERTAR,
                    new MapSqlParameterSource()
                            .addValue("id", especimen.id())
                            .addValue(
                                    "numeroDeAcceso", especimen.numeroDeAcceso().valor())
                            .addValue("pacienteId", especimen.pacienteId())
                            .addValue("tipo", especimen.tipo())
                            .addValue("estado", especimen.estado().name())
                            .addValue(
                                    "motivoDeRechazo",
                                    especimen.motivoDeRechazo().orElse(null)));
        } catch (DuplicateKeyException e) {
            throw new ConflictoDeNegocio("Ya hay una muestra con el número de acceso %s."
                    .formatted(especimen.numeroDeAcceso().valor()));
        }
    }

    @Override
    public Optional<Especimen> buscarPorId(UUID id) {
        return jdbc.query(BUSCAR_POR_ID, new MapSqlParameterSource("id", id), FILA_A_ESPECIMEN).stream()
                .findFirst();
    }

    @Override
    public List<Especimen> buscarDePaciente(UUID pacienteId) {
        return jdbc.query(BUSCAR_DE_PACIENTE, new MapSqlParameterSource("pacienteId", pacienteId), FILA_A_ESPECIMEN);
    }

    private static Especimen aEspecimen(ResultSet fila, int numeroDeFila) throws SQLException {
        return Especimen.reconstruir(
                UUID.fromString(fila.getString("id")),
                new NumeroDeAcceso(fila.getString("numero_de_acceso")),
                UUID.fromString(fila.getString("paciente_id")),
                fila.getString("tipo"),
                EstadoDeEspecimen.valueOf(fila.getString("estado")),
                fila.getString("motivo_de_rechazo"));
    }
}
