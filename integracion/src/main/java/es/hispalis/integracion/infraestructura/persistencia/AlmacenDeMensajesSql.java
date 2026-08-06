package es.hispalis.integracion.infraestructura.persistencia;

import es.hispalis.integracion.almacen.AlmacenDeMensajes;
import es.hispalis.integracion.almacen.MensajeEntrante;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * El almacén de mensajes sobre PostgreSQL, con SQL explícito.
 *
 * <p>La deduplicación <strong>la hace la base de datos</strong>, no una consulta previa en Java. Un
 * {@code SELECT} y luego un {@code INSERT} dejan una ventana entre los dos, y dos conexiones MLLP
 * simultáneas del mismo emisor la encuentran: las dos consultan, las dos ven que no está, y las dos
 * escriben en el laboratorio. La restricción única no tiene ventana.
 *
 * <p>Sin {@code @Transactional} a propósito: cada operación confirma por su cuenta. Si el mensaje se
 * apuntase dentro de la transacción del proceso, un fallo al escribir en la API FHIR se lo llevaría
 * por delante — y el archivo del motor existe precisamente para conservar lo que falló.
 */
@Repository
public class AlmacenDeMensajesSql implements AlmacenDeMensajes {

    private static final String INSERTAR =
            """
            INSERT INTO integracion.mensaje (
                id, aplicacion_emisora, instalacion_emisora, control_id, tipo, evento, estructura, version,
                charset_declarado, nhc, episodio, recibido_en, crudo, estado)
            VALUES (
                :id, :aplicacionEmisora, :instalacionEmisora, :controlId, :tipo, :evento, :estructura, :version,
                :charsetDeclarado, :nhc, :episodio, :recibidoEn, :crudo, 'RECIBIDO')
            """;

    private static final String ESTADO_DEL_YA_VISTO =
            """
            SELECT estado
              FROM integracion.mensaje
             WHERE aplicacion_emisora = :aplicacionEmisora
               AND instalacion_emisora = :instalacionEmisora
               AND control_id = :controlId
            """;

    /**
     * Un reintento reescribe la fila anterior en vez de crear otra: es el mismo mensaje del emisor, y
     * dos filas para un mismo {@code MSH-10} harían inútil el archivo como registro de qué pasó.
     */
    private static final String REABRIR =
            """
            UPDATE integracion.mensaje
               SET id = :id, estado = 'RECIBIDO', detalle = NULL, procesado_en = NULL,
                   recibido_en = :recibidoEn, crudo = :crudo, nhc = :nhc, episodio = :episodio,
                   charset_declarado = :charsetDeclarado
             WHERE aplicacion_emisora = :aplicacionEmisora
               AND instalacion_emisora = :instalacionEmisora
               AND control_id = :controlId
            """;

    private static final String MARCAR =
            """
            UPDATE integracion.mensaje
               SET estado = :estado, detalle = :detalle, procesado_en = :procesadoEn
             WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AlmacenDeMensajesSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Admision registrarSiEsNuevo(MensajeEntrante mensaje) {
        try {
            jdbc.update(INSERTAR, parametrosDe(mensaje));
            return Admision.NUEVO;
        } catch (DuplicateKeyException yaEstaba) {
            String estado = jdbc.queryForObject(ESTADO_DEL_YA_VISTO, claveDe(mensaje), String.class);
            if ("PROCESADO".equals(estado)) {
                return Admision.YA_PROCESADO;
            }
            jdbc.update(REABRIR, parametrosDe(mensaje));
            return Admision.REINTENTO;
        }
    }

    @Override
    public void marcarProcesado(UUID id, String referenciaProducida) {
        marcar(id, "PROCESADO", referenciaProducida);
    }

    @Override
    public void marcarRechazado(UUID id, String motivo) {
        marcar(id, "RECHAZADO", motivo);
    }

    private void marcar(UUID id, String estado, String detalle) {
        jdbc.update(
                MARCAR,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("estado", estado)
                        .addValue("detalle", detalle)
                        .addValue("procesadoEn", Timestamp.from(Instant.now())));
    }

    private static MapSqlParameterSource claveDe(MensajeEntrante mensaje) {
        return new MapSqlParameterSource()
                .addValue("aplicacionEmisora", mensaje.cabecera().aplicacionEmisora())
                .addValue("instalacionEmisora", mensaje.cabecera().instalacionEmisora())
                .addValue("controlId", mensaje.cabecera().controlId());
    }

    private static MapSqlParameterSource parametrosDe(MensajeEntrante mensaje) {
        return claveDe(mensaje)
                .addValue("id", mensaje.id())
                .addValue("tipo", mensaje.cabecera().tipo())
                .addValue("evento", mensaje.cabecera().evento())
                .addValue("estructura", mensaje.cabecera().estructuraDeclarada().orElse(null))
                .addValue("version", mensaje.cabecera().version())
                .addValue(
                        "charsetDeclarado",
                        mensaje.cabecera().charset().literal().orElse(null))
                .addValue("nhc", mensaje.nhc())
                .addValue("episodio", mensaje.episodio())
                .addValue("recibidoEn", Timestamp.from(mensaje.recibidoEn()))
                .addValue("crudo", mensaje.crudo());
    }
}
