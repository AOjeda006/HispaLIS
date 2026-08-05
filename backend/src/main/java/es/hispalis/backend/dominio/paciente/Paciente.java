package es.hispalis.backend.dominio.paciente;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Paciente atendido por el laboratorio. Agregado raíz.
 *
 * <p>No es un {@code Patient} de FHIR ni pretende serlo. Un {@code Patient} admite casi todo como
 * opcional y repetido, porque es un <strong>contrato de intercambio</strong> pensado para que
 * cualquier sistema del mundo pueda expresar lo que tenga; este agregado es lo que el laboratorio
 * necesita que sea cierto siempre (D3, §10 del diseño).
 *
 * <p>Lo que aquí se exige y allí no:
 *
 * <ul>
 *   <li><strong>NHC obligatorio y con formato</strong> — lo emite el laboratorio y es cómo se
 *       identifica al paciente internamente.
 *   <li><strong>Un apellido, sin trocear</strong> — sin él no hay a quién atribuir un resultado.
 * </ul>
 *
 * <p>Los identificadores que el laboratorio <em>no</em> emite —DNI/NIE, NUHSA, CIP-SNS, NASS— se
 * guardan como <strong>cadenas opacas y opcionales</strong>, sin validar su formato (D16). En un
 * laboratorio privado, un mutualista o un paciente extranjero llegan a diario sin ellos: exigirlos
 * o validarlos rechazaría pacientes reales.
 */
public final class Paciente {

    private final UUID id;
    private final Nhc nhc;
    private final NombrePersona nombre;
    private final String dniNie;
    private final String cipAutonomico;
    private final String cipSns;
    private final String nass;
    private final Sexo sexo;
    private final LocalDate fechaDeNacimiento;
    private final boolean activo;

    private Paciente(
            UUID id,
            Nhc nhc,
            NombrePersona nombre,
            String dniNie,
            String cipAutonomico,
            String cipSns,
            String nass,
            Sexo sexo,
            LocalDate fechaDeNacimiento,
            boolean activo) {
        this.id = id;
        this.nhc = nhc;
        this.nombre = nombre;
        this.dniNie = dniNie;
        this.cipAutonomico = cipAutonomico;
        this.cipSns = cipSns;
        this.nass = nass;
        this.sexo = sexo;
        this.fechaDeNacimiento = fechaDeNacimiento;
        this.activo = activo;
    }

    /**
     * Da de alta un paciente nuevo. Los invariantes se comprueban al construirlo, así que no existe
     * un {@code Paciente} en estado inválido.
     *
     * @param nhc número de historia clínica del laboratorio, obligatorio
     * @param nombre nombre y apellidos, obligatorio
     * @param sexo sexo administrativo; {@code null} se interpreta como {@link Sexo#DESCONOCIDO}
     * @param fechaDeNacimiento fecha de nacimiento, o {@code null} si el paciente llega sin filiar
     * @return el agregado, ya válido
     */
    public static Paciente darDeAlta(
            Nhc nhc,
            NombrePersona nombre,
            String dniNie,
            String cipAutonomico,
            String cipSns,
            String nass,
            Sexo sexo,
            LocalDate fechaDeNacimiento,
            boolean activo) {
        return new Paciente(
                UUID.randomUUID(),
                nhc,
                nombre,
                enBlancoEsNulo(dniNie),
                enBlancoEsNulo(cipAutonomico),
                enBlancoEsNulo(cipSns),
                enBlancoEsNulo(nass),
                sexo == null ? Sexo.DESCONOCIDO : sexo,
                fechaDeNacimiento,
                activo);
    }

    /** Reconstruye un paciente ya almacenado. Lo usa el repositorio, nunca un caso de uso. */
    public static Paciente reconstruir(
            UUID id,
            Nhc nhc,
            NombrePersona nombre,
            String dniNie,
            String cipAutonomico,
            String cipSns,
            String nass,
            Sexo sexo,
            LocalDate fechaDeNacimiento,
            boolean activo) {
        return new Paciente(id, nhc, nombre, dniNie, cipAutonomico, cipSns, nass, sexo, fechaDeNacimiento, activo);
    }

    /**
     * Que un identificador venga vacío no es que valga cadena vacía: es que no consta. Modelarlo
     * como {@code null} evita que una búsqueda por identificador encuentre a todos los pacientes
     * sin DNI como si compartieran uno.
     */
    private static String enBlancoEsNulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    public UUID id() {
        return id;
    }

    public Nhc nhc() {
        return nhc;
    }

    public NombrePersona nombre() {
        return nombre;
    }

    public Optional<String> dniNie() {
        return Optional.ofNullable(dniNie);
    }

    public Optional<String> cipAutonomico() {
        return Optional.ofNullable(cipAutonomico);
    }

    public Optional<String> cipSns() {
        return Optional.ofNullable(cipSns);
    }

    public Optional<String> nass() {
        return Optional.ofNullable(nass);
    }

    public Sexo sexo() {
        return sexo;
    }

    public Optional<LocalDate> fechaDeNacimiento() {
        return Optional.ofNullable(fechaDeNacimiento);
    }

    public boolean activo() {
        return activo;
    }
}
