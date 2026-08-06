package es.hispalis.integracion.arnes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Crea en la base de pruebas el esquema {@code outbox} <strong>del backend</strong>, ejecutando su
 * migración de verdad.
 *
 * <p>La alternativa —copiar aquí el {@code CREATE TABLE}— se desvía el día que el backend cambie una
 * columna, y el motor seguiría probando contra un esquema que ya no existe. Leer el fichero real hace
 * que ese cambio rompa este test, que es lo correcto: el motor <strong>lee</strong> esa tabla, así que
 * su forma es parte del contrato entre los dos servicios.
 *
 * <p>Es una dependencia de test entre módulos y está asumida: el mismo patrón que
 * {@code SistemasDeIdentificadorTest}, que cruza los {@code system} contra el FSH de la guía.
 */
public final class OutboxDelBackend {

    private static final Path MIGRACION =
            Path.of("..", "backend", "src", "main", "resources", "db", "migration", "V9__esquema_outbox.sql");

    private OutboxDelBackend() {
        // Utilidad.
    }

    /** Aplica la migración del outbox del backend, si no está ya. */
    public static void crear(DataSource origen) {
        JdbcTemplate jdbc = new JdbcTemplate(origen);
        Boolean yaEsta = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'outbox' AND table_name = 'hecho')",
                Boolean.class);
        if (Boolean.TRUE.equals(yaEsta)) {
            return;
        }

        String sql;
        try {
            sql = Files.readString(MIGRACION, StandardCharsets.UTF_8);
        } catch (IOException noEsta) {
            throw new UncheckedIOException(
                    ("No se encuentra la migración del outbox del backend en «%s». El motor lee esa tabla, así que "
                                    + "su esquema es parte del contrato y el test la aplica de verdad en vez de "
                                    + "copiarla.")
                            .formatted(MIGRACION.toAbsolutePath()),
                    noEsta);
        }
        jdbc.execute(sql);
    }

    /**
     * Apunta un hecho, tal y como lo dejaría el laboratorio dentro de su transacción.
     *
     * @param tipo el nombre del {@code TipoDeHecho}
     * @param paciente la clave de partición
     * @param carga referencias, nunca PHI
     * @return el identificador del hecho
     */
    public static UUID apuntar(DataSource origen, String tipo, UUID paciente, Map<String, String> carga) {
        UUID id = UUID.randomUUID();
        String json = carga.entrySet().stream()
                .map(entrada -> "\"%s\":\"%s\"".formatted(entrada.getKey(), entrada.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        new JdbcTemplate(origen)
                .update(
                        "INSERT INTO outbox.hecho (id, tipo, clave_de_particion, carga, creado_en) "
                                + "VALUES (?, ?, ?, ?::jsonb, ?)",
                        id,
                        tipo,
                        paciente,
                        json,
                        Timestamp.from(Instant.now()));
        return id;
    }

    /** Deja el outbox y el desplazamiento del motor como estaban. */
    public static void vaciar(DataSource origen) {
        JdbcTemplate jdbc = new JdbcTemplate(origen);
        jdbc.update("DELETE FROM integracion.hecho_consumido");
        jdbc.update("DELETE FROM outbox.hecho");
    }
}
