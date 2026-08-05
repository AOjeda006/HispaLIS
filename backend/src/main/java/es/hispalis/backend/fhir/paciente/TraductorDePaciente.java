package es.hispalis.backend.fhir.paciente;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.paciente.Nhc;
import es.hispalis.backend.dominio.paciente.NombrePersona;
import es.hispalis.backend.dominio.paciente.Paciente;
import es.hispalis.backend.dominio.paciente.Sexo;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import es.hispalis.backend.fhir.SistemasDeIdentificador;
import java.time.LocalDate;
import java.util.Optional;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.DateType;
import org.hl7.fhir.r5.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Component;

/**
 * La frontera entre el {@code Patient} de FHIR y el agregado {@link Paciente}.
 *
 * <p>Traduce en los dos sentidos, y no son simétricos:
 *
 * <ul>
 *   <li><strong>Entrada:</strong> del recurso se toma <em>solo</em> lo que el dominio modela. Lo que
 *       llegue de más no se guarda a escondidas: si algún día hace falta, se añade al agregado.
 *   <li><strong>Salida:</strong> el recurso se <strong>genera desde el dominio</strong>, no se
 *       devuelve el que llegó. Es lo que hace que la proyección sea una proyección: si el núcleo
 *       normaliza un apellido o descarta un identificador en blanco, eso es lo que se publica.
 * </ul>
 */
@Component
public class TraductorDePaciente {

    /** Extensiones estándar que descomponen el apellido español; van sobre {@code family}. */
    private static final String EXT_APELLIDO_PADRE = "http://hl7.org/fhir/StructureDefinition/humanname-fathers-family";

    private static final String EXT_APELLIDO_MADRE = "http://hl7.org/fhir/StructureDefinition/humanname-mothers-family";

    /** Construye el agregado a partir del recurso recibido, validando de paso sus invariantes. */
    public Paciente aDominio(Patient recurso) {
        return Paciente.darDeAlta(
                new Nhc(identificador(recurso, SistemasDeIdentificador.NHC)
                        .orElseThrow(
                                () -> new DatoInvalido("El paciente no trae número de historia clínica en el sistema "
                                        + SistemasDeIdentificador.NHC
                                        + ", que es obligatorio."))),
                nombreDe(recurso),
                identificador(recurso, SistemasDeIdentificador.DNI_NIE).orElse(null),
                identificador(recurso, SistemasDeIdentificador.CIP_AUTONOMICO).orElse(null),
                identificador(recurso, SistemasDeIdentificador.CIP_SNS).orElse(null),
                identificador(recurso, SistemasDeIdentificador.NASS).orElse(null),
                sexoDe(recurso),
                fechaDeNacimientoDe(recurso),
                !recurso.hasActive() || recurso.getActive());
    }

    /** Genera el recurso publicable a partir del agregado. Esta es la proyección. */
    public Patient aFhir(Paciente paciente) {
        Patient recurso = new Patient();
        // El id logico del recurso ES la identidad del agregado: asi una referencia `Patient/<uuid>`
        // que llega por la API resuelve al dominio sin tabla de correspondencias.
        recurso.setId(paciente.id().toString());
        recurso.getMeta().addProfile(PerfilesDeLaGuia.PACIENTE_LAB_ES.canonica());

        anadirIdentificador(recurso, SistemasDeIdentificador.NHC, paciente.nhc().valor());
        paciente.dniNie().ifPresent(v -> anadirIdentificador(recurso, SistemasDeIdentificador.DNI_NIE, v));
        paciente.cipAutonomico()
                .ifPresent(v -> anadirIdentificador(recurso, SistemasDeIdentificador.CIP_AUTONOMICO, v));
        paciente.cipSns().ifPresent(v -> anadirIdentificador(recurso, SistemasDeIdentificador.CIP_SNS, v));
        paciente.nass().ifPresent(v -> anadirIdentificador(recurso, SistemasDeIdentificador.NASS, v));

        recurso.addName(nombreFhirDe(paciente.nombre()));
        recurso.setGender(generoDe(paciente.sexo()));
        paciente.fechaDeNacimiento().ifPresent(fecha -> recurso.setBirthDateElement(new DateType(fecha.toString())));
        recurso.setActive(paciente.activo());
        return recurso;
    }

    private static HumanName nombreFhirDe(NombrePersona nombre) {
        HumanName humano = new HumanName().setUse(HumanName.NameUse.OFFICIAL);

        // El apellido completo va entero en `family`. Las extensiones lo DESCOMPONEN cuando se
        // conoce la descomposición; nunca sustituyen al valor completo ni se deducen de él.
        humano.setFamily(nombre.apellidos());
        if (nombre.apellidoPadre() != null) {
            humano.getFamilyElement().addExtension(EXT_APELLIDO_PADRE, new CodeType(nombre.apellidoPadre()));
        }
        if (nombre.apellidoMadre() != null) {
            humano.getFamilyElement().addExtension(EXT_APELLIDO_MADRE, new CodeType(nombre.apellidoMadre()));
        }
        if (!nombre.nombreDePila().isBlank()) {
            humano.addGiven(nombre.nombreDePila());
        }
        return humano;
    }

    private static NombrePersona nombreDe(Patient recurso) {
        HumanName nombre = recurso.getNameFirstRep();
        return new NombrePersona(
                nombre.getFamily(),
                nombre.getGivenAsSingleString(),
                extensionDeApellido(nombre, EXT_APELLIDO_PADRE),
                extensionDeApellido(nombre, EXT_APELLIDO_MADRE));
    }

    private static String extensionDeApellido(HumanName nombre, String url) {
        if (!nombre.hasFamilyElement() || !nombre.getFamilyElement().hasExtension(url)) {
            return null;
        }
        return nombre.getFamilyElement().getExtensionByUrl(url).getValue().primitiveValue();
    }

    private static Optional<String> identificador(Patient recurso, String system) {
        return recurso.getIdentifier().stream()
                .filter(identificador -> system.equals(identificador.getSystem()))
                .map(Identifier::getValue)
                .filter(valor -> valor != null && !valor.isBlank())
                .findFirst();
    }

    private static void anadirIdentificador(Patient recurso, String system, String valor) {
        recurso.addIdentifier().setSystem(system).setValue(valor);
    }

    private static LocalDate fechaDeNacimientoDe(Patient recurso) {
        if (!recurso.hasBirthDateElement() || !recurso.getBirthDateElement().hasValue()) {
            return null;
        }
        return LocalDate.parse(recurso.getBirthDateElement().asStringValue());
    }

    private static Sexo sexoDe(Patient recurso) {
        if (recurso.getGender() == null) {
            return Sexo.DESCONOCIDO;
        }
        return switch (recurso.getGender()) {
            case FEMALE -> Sexo.MUJER;
            case MALE -> Sexo.HOMBRE;
            case OTHER -> Sexo.OTRO;
            default -> Sexo.DESCONOCIDO;
        };
    }

    private static AdministrativeGender generoDe(Sexo sexo) {
        return switch (sexo) {
            case MUJER -> AdministrativeGender.FEMALE;
            case HOMBRE -> AdministrativeGender.MALE;
            case OTRO -> AdministrativeGender.OTHER;
            case DESCONOCIDO -> AdministrativeGender.UNKNOWN;
        };
    }
}
