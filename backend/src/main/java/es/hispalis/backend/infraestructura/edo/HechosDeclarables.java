package es.hispalis.backend.infraestructura.edo;

import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Lo que el notificador EDO todavía no ha mirado del {@code outbox}, y cómo cierra cada fila.
 *
 * <p><strong>Lleva su propio desplazamiento</strong> en {@code edo.hecho_consumido}, y no marca
 * {@code outbox.hecho.publicado_en}: esa casilla es del relay a Kafka, y dos consumidores marcando la
 * misma dejarían al primero sin su hecho. Es lo que hace cualquier grupo de consumidores, y lo que ya
 * hacía el motor de integración con el {@code ORU} saliente.
 *
 * <p>El día que el bus esté delante de verdad, esta clase es lo que se sustituye por un consumidor de
 * Kafka con su grupo. El notificador de arriba <strong>no cambia</strong>: lo que se está probando hoy
 * —que el envío se dispara desde el hecho— seguirá valiendo.
 */
class HechosDeclarables {

    /**
     * Lo pendiente, en el orden en que ocurrió.
     *
     * <p>Se piden <strong>todos</strong> los tipos y no solo el declarable. Filtrar en el {@code SQL}
     * sería más corto y dejaría el resto de hechos sin anotar, así que la consulta arrastraría para
     * siempre lo que este consumidor no mira; anotándolos como descartados, el desplazamiento avanza.
     */
    private static final String PENDIENTES =
            """
            SELECT h.id, h.tipo, h.carga->>'observationRef' AS observacion, h.creado_en
              FROM outbox.hecho h
             WHERE NOT EXISTS (SELECT 1 FROM edo.hecho_consumido c WHERE c.hecho_id = h.id)
             ORDER BY h.creado_en, h.id
             LIMIT :tanda
            """;

    private static final String ANOTAR =
            """
            INSERT INTO edo.hecho_consumido (hecho_id, consumo, detalle, consumido_en)
            VALUES (:hechoId, :consumo, :detalle, :cuando)
            ON CONFLICT (hecho_id) DO NOTHING
            """;

    /**
     * Un hecho del outbox visto por este consumidor.
     *
     * @param observacion la referencia al resultado, {@code Observation/<uuid>}, o {@code null}
     * @param ocurridoEn cuándo se apuntó, que para un {@code RESULTADO_DECLARABLE} es cuando se validó
     *     — y por tanto cuándo empieza a correr el plazo
     */
    record HechoPendiente(UUID id, TipoDeHecho tipo, String observacion, Instant ocurridoEn) {}

    private final NamedParameterJdbcTemplate jdbc;

    HechosDeclarables(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<HechoPendiente> pendientes(int tanda) {
        return jdbc.query(
                PENDIENTES,
                new MapSqlParameterSource("tanda", tanda),
                (fila, numero) -> new HechoPendiente(
                        fila.getObject("id", UUID.class),
                        TipoDeHecho.valueOf(fila.getString("tipo")),
                        fila.getString("observacion"),
                        fila.getTimestamp("creado_en").toInstant()));
    }

    void anotarAtendido(UUID hechoId, String detalle) {
        anotar(hechoId, "ATENDIDO", detalle);
    }

    void anotarDescartado(UUID hechoId, String detalle) {
        anotar(hechoId, "DESCARTADO", detalle);
    }

    private void anotar(UUID hechoId, String consumo, String detalle) {
        jdbc.update(
                ANOTAR,
                new MapSqlParameterSource()
                        .addValue("hechoId", hechoId)
                        .addValue("consumo", consumo)
                        .addValue("detalle", detalle)
                        .addValue("cuando", Timestamp.from(Instant.now())));
    }
}
