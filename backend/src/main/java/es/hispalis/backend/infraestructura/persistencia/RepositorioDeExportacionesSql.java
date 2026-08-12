package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.exportacion.EstadoDeExportacion;
import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion.Fichero;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistencia de los trabajos de exportación. Ver {@link RepositorioDePacientesSql} para el porqué
 * del SQL.
 *
 * <p>El guardado es un <em>upsert</em> del trabajo más un reemplazo completo de sus ficheros. Reemplazar
 * en vez de sincronizar por diferencias no es pereza: la lista de ficheros de una exportación cambia
 * exactamente dos veces —cuando termina y cuando se cierra— y en la segunda pasa a estar vacía, que es
 * justo lo que hay que poder garantizar.
 */
@Repository
public class RepositorioDeExportacionesSql implements RepositorioDeExportaciones {

    private static final String GUARDAR_TRABAJO =
            """
            INSERT INTO exportacion.trabajo (id, cohorte, solicitante, corte, estado, caduca_en, motivo_fallo)
            VALUES (:id, :cohorte, :solicitante, :corte, :estado, :caducaEn, :motivoFallo)
            ON CONFLICT (id) DO UPDATE
               SET estado = EXCLUDED.estado,
                   caduca_en = EXCLUDED.caduca_en,
                   motivo_fallo = EXCLUDED.motivo_fallo
            """;

    private static final String BORRAR_FICHEROS = "DELETE FROM exportacion.fichero WHERE trabajo_id = :id";

    private static final String GUARDAR_FICHERO =
            """
            INSERT INTO exportacion.fichero (billete, trabajo_id, tipo_recurso, nombre, recursos)
            VALUES (:billete, :trabajoId, :tipoRecurso, :nombre, :recursos)
            """;

    private static final String COLUMNAS = "id, cohorte, solicitante, corte, estado, caduca_en, motivo_fallo";

    private static final String POR_ID = "SELECT " + COLUMNAS + " FROM exportacion.trabajo WHERE id = :id";

    /** Terminadas cuyo plazo pasó. El índice parcial de la V16 es exactamente este predicado. */
    private static final String CADUCADAS = "SELECT " + COLUMNAS + " FROM exportacion.trabajo "
            + "WHERE estado = 'TERMINADA' AND caduca_en <= :ahora ORDER BY caduca_en";

    /**
     * Todo trabajo que todavía puede tener ficheros suyos en el disco.
     *
     * <p>Incluye {@code EN_CURSO}: está escribiéndolos ahora mismo, y el barrendero que lo diera por
     * huérfano le borraría la exportación a medias a un cliente que está esperándola.
     */
    private static final String VIVAS = "SELECT id FROM exportacion.trabajo WHERE estado IN ('EN_CURSO', 'TERMINADA')";

    private static final String FICHEROS_DE = "SELECT billete, tipo_recurso, nombre, recursos FROM exportacion.fichero "
            + "WHERE trabajo_id = :id ORDER BY tipo_recurso";

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDeExportacionesSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(TrabajoDeExportacion trabajo) {
        jdbc.update(
                GUARDAR_TRABAJO,
                new MapSqlParameterSource()
                        .addValue("id", trabajo.id())
                        .addValue("cohorte", trabajo.cohorte())
                        .addValue("solicitante", trabajo.solicitante().orElse(null))
                        .addValue("corte", Timestamp.from(trabajo.corte()))
                        .addValue("estado", trabajo.estado().name())
                        .addValue(
                                "caducaEn",
                                trabajo.caducaEn().map(Timestamp::from).orElse(null))
                        .addValue("motivoFallo", trabajo.motivoDelFallo().orElse(null)));

        jdbc.update(BORRAR_FICHEROS, new MapSqlParameterSource("id", trabajo.id()));
        for (Fichero fichero : trabajo.ficherosSinExigirEstado()) {
            jdbc.update(
                    GUARDAR_FICHERO,
                    new MapSqlParameterSource()
                            .addValue("billete", fichero.billete())
                            .addValue("trabajoId", trabajo.id())
                            .addValue("tipoRecurso", fichero.tipoDeRecurso())
                            .addValue("nombre", fichero.nombre())
                            .addValue("recursos", fichero.recursos()));
        }
    }

    @Override
    public Optional<TrabajoDeExportacion> buscar(UUID id) {
        return jdbc.query(POR_ID, new MapSqlParameterSource("id", id), this::aTrabajo).stream()
                .findFirst();
    }

    @Override
    public List<TrabajoDeExportacion> caducadas(Instant ahora) {
        return jdbc.query(CADUCADAS, new MapSqlParameterSource("ahora", Timestamp.from(ahora)), this::aTrabajo);
    }

    @Override
    public Set<UUID> vivas() {
        return new HashSet<>(jdbc.query(VIVAS, (fila, numero) -> fila.getObject("id", UUID.class)));
    }

    private TrabajoDeExportacion aTrabajo(ResultSet fila, int numero) throws SQLException {
        UUID id = fila.getObject("id", UUID.class);
        return TrabajoDeExportacion.reconstruir(
                id,
                fila.getString("cohorte"),
                Optional.ofNullable(fila.getString("solicitante")),
                fila.getTimestamp("corte").toInstant(),
                EstadoDeExportacion.valueOf(fila.getString("estado")),
                ficherosDe(id),
                Optional.ofNullable(fila.getTimestamp("caduca_en")).map(Timestamp::toInstant),
                Optional.ofNullable(fila.getString("motivo_fallo")));
    }

    private List<Fichero> ficherosDe(UUID trabajo) {
        return jdbc.query(
                FICHEROS_DE,
                new MapSqlParameterSource("id", trabajo),
                (fila, numero) -> new Fichero(
                        fila.getString("billete"),
                        fila.getString("tipo_recurso"),
                        fila.getString("nombre"),
                        fila.getLong("recursos")));
    }
}
