package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.resultado.Disparo;
import es.hispalis.backend.dominio.resultado.Medicion;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.dominio.resultado.TipoDeDisparo;
import es.hispalis.backend.dominio.resultado.Validacion;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
                valor_codificado, medido_en, realizado_por, disparo_origen, disparo_tipo, disparo_motivo)
            VALUES (
                :id, :especimenId, :pacienteId, :peticionId, :codigoDePrueba, :valor, :unidadUcum, :valorTextual,
                :valorCodificado, :medidoEn, :realizadoPor, :disparoOrigen, :disparoTipo, :disparoMotivo)
            """;

    private static final String FIJAR_LAS_FIRMAS_EXIGIDAS =
            """
            UPDATE dominio.resultado
               SET firmas_exigidas = :firmasExigidas
             WHERE id = :id
            """;

    /**
     * La firma que falta. {@code ON CONFLICT DO NOTHING} porque escribir dos veces la misma firma no
     * es un error: el reconciliador vuelve a pasar por aquí y tiene que poder hacerlo sin romperse.
     * Lo que sí es un error —la misma persona firmando dos veces— lo para la unicidad de la V13, y ese
     * camino no llega hasta aquí porque el dominio lo corta antes.
     */
    private static final String FIRMAR =
            """
            INSERT INTO dominio.validacion_de_resultado (resultado_id, orden, facultativo, realizada_en)
            VALUES (:resultadoId, :orden, :facultativo, :realizadaEn)
            ON CONFLICT (resultado_id, orden) DO NOTHING
            """;

    /**
     * Las firmas vienen en dos columnas de array y no en un {@code JOIN}, para que una consulta siga
     * devolviendo una fila por resultado: con el {@code JOIN}, un resultado con dos firmas saldría
     * dos veces y el mapeador tendría que agrupar. Van ordenadas por {@code orden}, que es lo que
     * distingue la revisión inicial de la contra-revisión.
     */
    private static final String COLUMNAS =
            """
            r.id, r.especimen_id, r.paciente_id, r.peticion_id, r.codigo_de_prueba, r.valor, r.unidad_ucum,
            r.valor_textual, r.valor_codificado, r.medido_en, r.realizado_por, r.firmas_exigidas,
            r.disparo_origen, r.disparo_tipo, r.disparo_motivo,
            (SELECT array_agg(v.facultativo ORDER BY v.orden)
               FROM dominio.validacion_de_resultado v WHERE v.resultado_id = r.id) AS firmantes,
            (SELECT array_agg(v.realizada_en ORDER BY v.orden)
               FROM dominio.validacion_de_resultado v WHERE v.resultado_id = r.id) AS firmado_en
            """;

    private static final String BUSCAR_POR_ID = "SELECT " + COLUMNAS + " FROM dominio.resultado r WHERE r.id = :id";

    private static final String LINEAS_CON_RESULTADO =
            """
            SELECT DISTINCT peticion_id
              FROM dominio.resultado
             WHERE peticion_id IN (:lineas)
            """;

    private static final String BUSCAR_DE_PACIENTE = "SELECT " + COLUMNAS
            + """
             FROM dominio.resultado r
            WHERE r.paciente_id = :pacienteId
            ORDER BY r.codigo_de_prueba, r.id
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
                        .addValue("valorCodificado", resultado.valorCodificado().orElse(null))
                        .addValue(
                                "medidoEn",
                                resultado
                                        .medicion()
                                        .realizadaEn()
                                        .map(Timestamp::from)
                                        .orElse(null))
                        .addValue(
                                "realizadoPor",
                                resultado.medicion().realizadaPor().orElse(null))
                        .addValue(
                                "disparoOrigen",
                                resultado.disparadoPor().map(Disparo::origen).orElse(null))
                        .addValue(
                                "disparoTipo",
                                resultado
                                        .disparadoPor()
                                        .map(Disparo::tipo)
                                        .map(TipoDeDisparo::codigoFhir)
                                        .orElse(null))
                        .addValue(
                                "disparoMotivo",
                                resultado.disparadoPor().map(Disparo::motivo).orElse(null)));
    }

    @Override
    public void actualizar(Resultado resultado) {
        // Solo se escribe lo de la validación. El valor medido y su unidad no cambian al validar:
        // validar es responder de una cifra, no reescribirla. Un `UPDATE` que las tocase convertiría
        // una firma en una corrección silenciosa.
        jdbc.update(
                FIJAR_LAS_FIRMAS_EXIGIDAS,
                new MapSqlParameterSource()
                        .addValue("id", resultado.id())
                        .addValue("firmasExigidas", resultado.firmasExigidas().orElse(null)));

        // Se escriben todas y no solo la última: así el método vale igual para la primera firma, para
        // la segunda y para el reconciliador, que regenera desde el dominio sin saber qué había.
        List<Validacion> firmas = resultado.firmas();
        for (int posicion = 0; posicion < firmas.size(); posicion++) {
            Validacion firma = firmas.get(posicion);
            jdbc.update(
                    FIRMAR,
                    new MapSqlParameterSource()
                            .addValue("resultadoId", resultado.id())
                            .addValue("orden", posicion + 1)
                            .addValue("facultativo", firma.facultativo())
                            .addValue("realizadaEn", Timestamp.from(firma.realizadaEn())));
        }
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

    @Override
    public List<Resultado> buscarDePaciente(UUID pacienteId) {
        return jdbc.query(BUSCAR_DE_PACIENTE, new MapSqlParameterSource("pacienteId", pacienteId), FILA_A_RESULTADO);
    }

    private static Resultado aResultado(ResultSet fila, int numeroDeFila) throws SQLException {
        String peticionId = fila.getString("peticion_id");
        Timestamp medidoEn = fila.getTimestamp("medido_en");
        int firmasExigidas = fila.getInt("firmas_exigidas");
        return Resultado.reconstruir(
                UUID.fromString(fila.getString("id")),
                UUID.fromString(fila.getString("especimen_id")),
                UUID.fromString(fila.getString("paciente_id")),
                peticionId == null ? null : UUID.fromString(peticionId),
                fila.getString("codigo_de_prueba"),
                fila.getBigDecimal("valor"),
                fila.getString("unidad_ucum"),
                fila.getString("valor_textual"),
                fila.getString("valor_codificado"),
                Medicion.de(medidoEn == null ? null : medidoEn.toInstant(), fila.getString("realizado_por")),
                firmasDe(fila),
                fila.wasNull() ? null : firmasExigidas,
                disparoDe(fila));
    }

    /**
     * Las firmas que trae la fila, en el orden en que se pusieron.
     *
     * <p>Los dos arrays vienen del mismo {@code array_agg} sobre la misma tabla y el mismo criterio
     * de orden, así que casan posición a posición. Si el resultado no tiene ninguna firma,
     * {@code array_agg} devuelve {@code NULL} —no un array vacío—, y eso hay que mirarlo.
     */
    private static List<Validacion> firmasDe(ResultSet fila) throws SQLException {
        Array firmantes = fila.getArray("firmantes");
        if (firmantes == null) {
            return List.of();
        }
        String[] quienes = (String[]) firmantes.getArray();
        Timestamp[] cuandos = (Timestamp[]) fila.getArray("firmado_en").getArray();

        List<Validacion> firmas = new ArrayList<>(quienes.length);
        for (int posicion = 0; posicion < quienes.length; posicion++) {
            firmas.add(Validacion.por(quienes[posicion], cuandos[posicion].toInstant()));
        }
        return firmas;
    }

    /** El disparo, si esta determinación viene de otra. La restricción de la V11 garantiza los tres o ninguno. */
    private static Disparo disparoDe(ResultSet fila) throws SQLException {
        String origen = fila.getString("disparo_origen");
        if (origen == null) {
            return null;
        }
        return new Disparo(
                UUID.fromString(origen),
                TipoDeDisparo.deCodigoFhir(fila.getString("disparo_tipo")),
                fila.getString("disparo_motivo"));
    }
}
