package es.hispalis.backend.infraestructura.persistencia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.dominio.hecho.Hecho;
import es.hispalis.backend.dominio.hecho.RepositorioDeHechos;
import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistencia del {@code outbox}. Ver {@link RepositorioDePacientesSql} para el porqué del SQL.
 *
 * <p>El {@code INSERT} va por el mismo {@code NamedParameterJdbcTemplate} que el resto del dominio, y
 * eso es lo que hace que el hecho entre en la <strong>misma transacción</strong> que el agregado y
 * que la proyección: la conexión es la que la transacción JPA ya tiene abierta.
 *
 * <p>La carga se serializa aquí y no en el agregado. El dominio la tiene como un mapa de referencias
 * —que es lo que es—; que por debajo se guarde como {@code jsonb} es una decisión de este adaptador.
 */
@Repository
public class RepositorioDeHechosSql implements RepositorioDeHechos {

    private static final String INSERTAR =
            """
            INSERT INTO outbox.hecho (id, tipo, clave_de_particion, carga, creado_en, publicado_en)
            VALUES (:id, :tipo, :claveDeParticion, CAST(:carga AS jsonb), :creadoEn, NULL)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public RepositorioDeHechosSql(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void registrar(Hecho hecho) {
        jdbc.update(
                INSERTAR,
                new MapSqlParameterSource()
                        .addValue("id", hecho.id())
                        .addValue("tipo", hecho.tipo().name())
                        .addValue("claveDeParticion", hecho.claveDeParticion())
                        .addValue("carga", serializar(hecho))
                        .addValue("creadoEn", Timestamp.from(hecho.creadoEn())));
    }

    /**
     * La carga son cadenas cortas y planas, así que esto no puede fallar por el contenido. Si falla,
     * es un fallo de programación y no una escritura que haya que reintentar: se deja reventar la
     * transacción entera, que es exactamente lo que debe pasar — un hecho a medias no se publica.
     */
    private String serializar(Hecho hecho) {
        try {
            return json.writeValueAsString(hecho.carga());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la carga del hecho " + hecho.id(), e);
        }
    }
}
