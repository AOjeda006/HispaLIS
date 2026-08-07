package es.hispalis.backend.infraestructura.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * El lado de lectura del {@code outbox}: lo que queda por sacar, y cómo se cierra cada fila.
 *
 * <p>Vive con el relay y no en {@code infraestructura/persistencia} a propósito. Aquello son
 * adaptadores de puertos del dominio; esto no lo es: el dominio no sabe que existe un relay ni tiene
 * por qué. Es una consulta que solo entiende el bus, y partirla en dos paquetes dejaría medio
 * adaptador lejos del único código que lo usa.
 *
 * <p>La fila <strong>nunca se borra</strong>. Es la prueba de qué apuntó el laboratorio y de qué
 * salió al bus, y el día que un consumidor discuta si recibió algo, esta tabla es lo que responde.
 */
class BandejaDeSalida {

    /**
     * Lo pendiente, en el orden en que ocurrió.
     *
     * <p>Sin {@code FOR UPDATE SKIP LOCKED}, y es deliberado: bloquear la fila obligaría a mantener
     * la transacción abierta mientras se habla con el broker —E/S de red dentro de una transacción de
     * base de datos, con el broker caído hasta que salte el <em>timeout</em>—. El contrato declarado
     * es <strong>al menos una vez</strong>, así que dos relays publicando el mismo hecho es un
     * duplicado, no una avería: el consumidor deduplica por {@code hechoId}. Cambiar esto por
     * exactamente-una-vez costaría transacciones distribuidas y no compraría nada que la
     * idempotencia no dé ya.
     */
    private static final String PENDIENTES =
            """
            SELECT id, tipo, clave_de_particion, carga::text AS carga, creado_en
              FROM outbox.hecho
             WHERE publicado_en IS NULL AND descartado_en IS NULL
             ORDER BY creado_en, id
             LIMIT :tanda
            """;

    private static final String MARCAR_PUBLICADO =
            """
            UPDATE outbox.hecho
               SET publicado_en = :cuando, topico = :topico
             WHERE id = :id AND publicado_en IS NULL
            """;

    private static final String MARCAR_DESCARTADO =
            """
            UPDATE outbox.hecho
               SET descartado_en = :cuando
             WHERE id = :id AND descartado_en IS NULL
            """;

    private static final TypeReference<Map<String, String>> CARGA = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    BandejaDeSalida(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<HechoPendiente> pendientes(int tanda) {
        return jdbc.query(
                PENDIENTES,
                new MapSqlParameterSource("tanda", tanda),
                (fila, numero) -> new HechoPendiente(
                        fila.getObject("id", UUID.class),
                        TipoDeHecho.valueOf(fila.getString("tipo")),
                        fila.getObject("clave_de_particion", UUID.class),
                        deserializar(fila.getString("carga")),
                        fila.getTimestamp("creado_en").toInstant()));
    }

    void marcarPublicado(UUID id, Topico topico) {
        jdbc.update(
                MARCAR_PUBLICADO,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("topico", topico.nombre())
                        .addValue("cuando", Timestamp.from(Instant.now())));
    }

    void marcarDescartado(UUID id) {
        jdbc.update(
                MARCAR_DESCARTADO,
                new MapSqlParameterSource().addValue("id", id).addValue("cuando", Timestamp.from(Instant.now())));
    }

    private Map<String, String> deserializar(String carga) {
        try {
            return json.readValue(carga, CARGA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("La carga del hecho no es el objeto plano que escribió el dominio.", e);
        }
    }
}
