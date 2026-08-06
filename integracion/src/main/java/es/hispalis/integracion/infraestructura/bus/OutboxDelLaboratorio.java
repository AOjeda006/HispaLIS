package es.hispalis.integracion.infraestructura.bus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.integracion.bus.BusDeHechos;
import es.hispalis.integracion.bus.HechoDelLaboratorio;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * El bus, leído de la tabla del {@code outbox} del laboratorio.
 *
 * <p>Es el sustituto de Kafka hasta el ítem 30, y está escrito para que ese cambio sea de una clase:
 * la consulta devuelve exactamente lo que un consumidor de Kafka recibiría, en el mismo orden.
 *
 * <p><strong>Consulta el {@code outbox} y nada más.</strong> Ni {@code dominio}, ni el esquema de
 * HAPI. Si algún día aparece aquí un {@code JOIN} contra {@code dominio.paciente} para ahorrarse una
 * llamada a la API, D5 está roto y el motor ha dejado de ser un cliente.
 */
@Repository
public class OutboxDelLaboratorio implements BusDeHechos {

    private static final String SIN_CONSUMIR =
            """
            SELECT h.id, h.tipo, h.clave_de_particion, h.carga, h.creado_en
              FROM outbox.hecho h
             WHERE NOT EXISTS (
                       SELECT 1 FROM integracion.hecho_consumido c WHERE c.hecho_id = h.id)
             ORDER BY h.creado_en, h.id
             LIMIT :limite
            """;

    private static final String ANOTAR =
            """
            INSERT INTO integracion.hecho_consumido (hecho_id, consumido_en, resultado, detalle)
            VALUES (:hechoId, :cuando, :resultado, :detalle)
            ON CONFLICT (hecho_id) DO UPDATE
                SET consumido_en = EXCLUDED.consumido_en,
                    resultado    = EXCLUDED.resultado,
                    detalle      = EXCLUDED.detalle
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxDelLaboratorio(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<HechoDelLaboratorio> sinConsumir(int limite) {
        return jdbc.query(SIN_CONSUMIR, new MapSqlParameterSource("limite", limite), fila());
    }

    @Override
    public void anotarConsumo(UUID hechoId, Consumo consumo, String detalle) {
        jdbc.update(
                ANOTAR,
                new MapSqlParameterSource()
                        .addValue("hechoId", hechoId)
                        .addValue("cuando", Timestamp.from(Instant.now()))
                        .addValue("resultado", consumo.name())
                        .addValue("detalle", detalle));
    }

    private RowMapper<HechoDelLaboratorio> fila() {
        return (fila, numero) -> new HechoDelLaboratorio(
                fila.getObject("id", UUID.class),
                fila.getString("tipo"),
                fila.getObject("clave_de_particion", UUID.class),
                cargaDe(fila.getString("carga")),
                fila.getTimestamp("creado_en").toInstant());
    }

    private Map<String, String> cargaDe(String jsonb) {
        try {
            return json.readValue(jsonb, new TypeReference<Map<String, String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException noEsUnObjeto) {
            // Un hecho cuya carga no se puede leer no se puede consumir, y no es reparable desde
            // aquí. Se devuelve vacío: el notificador lo descartará diciendo qué le falta, en vez de
            // tumbar el sondeo entero y bloquear todos los hechos que vienen detrás.
            return Map.of();
        }
    }
}
