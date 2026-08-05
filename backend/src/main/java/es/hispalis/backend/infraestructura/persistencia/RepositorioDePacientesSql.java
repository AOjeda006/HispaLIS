package es.hispalis.backend.infraestructura.persistencia;

import es.hispalis.backend.dominio.ConflictoDeNegocio;
import es.hispalis.backend.dominio.paciente.Nhc;
import es.hispalis.backend.dominio.paciente.NombrePersona;
import es.hispalis.backend.dominio.paciente.Paciente;
import es.hispalis.backend.dominio.paciente.RepositorioDePacientes;
import es.hispalis.backend.dominio.paciente.Sexo;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistencia del agregado {@link Paciente} en el esquema {@code dominio}, con SQL explícito.
 *
 * <p><strong>Por qué SQL y no Spring Data JPA</strong>, que es la convención del proyecto: el
 * {@code EntityManagerFactory} de esta aplicación <em>no es nuestro</em>, lo construye HAPI para sus
 * propias entidades (ADR-0011). Meter las nuestras dentro obligaría a reproducir a mano la lista de
 * paquetes que HAPI escanea, que es interna suya y puede cambiar en cualquier versión menor.
 *
 * <p>A cambio no se pierde nada que aquí importe. La transacción es la misma —el
 * {@code JpaTransactionManager} tiene fijado el {@code DataSource}, así que el JDBC de Spring toma
 * la conexión que ya está en curso—, el agregado queda <strong>libre de anotaciones de JPA</strong>,
 * que es lo que Clean Architecture pide de un núcleo de dominio, y el esquema lo gobierna Flyway sin
 * competir con ningún {@code ddl-auto}.
 */
@Repository
public class RepositorioDePacientesSql implements RepositorioDePacientes {

    private static final String INSERTAR =
            """
            INSERT INTO dominio.paciente (
                id, nhc, apellidos, nombre_de_pila, apellido_padre, apellido_madre,
                dni_nie, cip_autonomico, cip_sns, nass, sexo, fecha_nacimiento, activo)
            VALUES (
                :id, :nhc, :apellidos, :nombreDePila, :apellidoPadre, :apellidoMadre,
                :dniNie, :cipAutonomico, :cipSns, :nass, :sexo, :fechaNacimiento, :activo)
            """;

    private static final String BUSCAR_POR_NHC =
            """
            SELECT id, nhc, apellidos, nombre_de_pila, apellido_padre, apellido_madre,
                   dni_nie, cip_autonomico, cip_sns, nass, sexo, fecha_nacimiento, activo
              FROM dominio.paciente
             WHERE nhc = :nhc
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RepositorioDePacientesSql(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void guardar(Paciente paciente) {
        try {
            jdbc.update(INSERTAR, parametrosDe(paciente));
        } catch (DuplicateKeyException e) {
            // La unicidad del NHC se comprueba en la base de datos y no antes con un `SELECT`:
            // entre la consulta y la inserción cabe otra alta, y el índice único no se equivoca.
            throw new ConflictoDeNegocio(
                    "Ya hay un paciente con el número de historia clínica " + paciente.nhc() + ".");
        }
    }

    @Override
    public Optional<Paciente> buscarPorNhc(Nhc nhc) {
        return jdbc.query(BUSCAR_POR_NHC, new MapSqlParameterSource("nhc", nhc.valor()), FILA_A_PACIENTE).stream()
                .findFirst();
    }

    private static MapSqlParameterSource parametrosDe(Paciente paciente) {
        return new MapSqlParameterSource()
                .addValue("id", paciente.id())
                .addValue("nhc", paciente.nhc().valor())
                .addValue("apellidos", paciente.nombre().apellidos())
                .addValue("nombreDePila", paciente.nombre().nombreDePila())
                .addValue("apellidoPadre", paciente.nombre().apellidoPadre())
                .addValue("apellidoMadre", paciente.nombre().apellidoMadre())
                .addValue("dniNie", paciente.dniNie().orElse(null))
                .addValue("cipAutonomico", paciente.cipAutonomico().orElse(null))
                .addValue("cipSns", paciente.cipSns().orElse(null))
                .addValue("nass", paciente.nass().orElse(null))
                .addValue("sexo", paciente.sexo().name())
                .addValue(
                        "fechaNacimiento",
                        paciente.fechaDeNacimiento().map(Date::valueOf).orElse(null))
                .addValue("activo", paciente.activo());
    }

    private static final RowMapper<Paciente> FILA_A_PACIENTE = RepositorioDePacientesSql::aPaciente;

    private static Paciente aPaciente(ResultSet fila, int numeroDeFila) throws SQLException {
        Date nacimiento = fila.getDate("fecha_nacimiento");
        return Paciente.reconstruir(
                UUID.fromString(fila.getString("id")),
                new Nhc(fila.getString("nhc")),
                new NombrePersona(
                        fila.getString("apellidos"),
                        fila.getString("nombre_de_pila"),
                        fila.getString("apellido_padre"),
                        fila.getString("apellido_madre")),
                fila.getString("dni_nie"),
                fila.getString("cip_autonomico"),
                fila.getString("cip_sns"),
                fila.getString("nass"),
                Sexo.valueOf(fila.getString("sexo")),
                nacimiento == null ? null : nacimiento.toLocalDate(),
                fila.getBoolean("activo"));
    }
}
