package es.hispalis.integracion.canal.adt;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.v251.datatype.CX;
import ca.uhn.hl7v2.model.v251.datatype.XPN;
import ca.uhn.hl7v2.model.v251.segment.PID;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import es.hispalis.integracion.hl7.Campos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r5.model.DateType;
import org.hl7.fhir.r5.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Component;

/**
 * El transformador del canal de demografía: {@code PID} de un {@code ADT} → {@code Patient} de R5.
 *
 * <p>Es una clase sin estado y sin dependencias a propósito: el mapeo es lo que hay que poder leer,
 * discutir y probar sin levantar nada. Es también el sitio donde este proyecto se juega dos cosas que
 * en un laboratorio se pagan caras.
 *
 * <h2>Los apellidos no se parten por el espacio</h2>
 *
 * <p>{@code PID-5.1} trae el nombre familiar <strong>completo</strong> y así entra en
 * {@code HumanName.family}. «De la Torre Gómez» y «Fernández de Córdoba Ruiz» son dos apellidos, no
 * tres ni cuatro, y ningún heurístico sobre espacios acierta con ellos. Confundir apellidos en un
 * laboratorio es confundir pacientes.
 *
 * <p>Las extensiones {@code humanname-fathers-family} y {@code humanname-mothers-family}
 * <strong>solo se rellenan si el emisor manda la descomposición</strong>, y V2.5.1 no tiene sitio
 * estándar para ella: {@code FN} descompone en «propio» y «del cónyuge», que es otra cosa. Mientras
 * no haya un acuerdo escrito con el HIS sobre dónde viajan los dos apellidos, el motor
 * <strong>no los deduce</strong> y las extensiones no se emiten. Es información que no tenemos, y
 * fabricarla sería peor que no tenerla.
 *
 * <h2>Los identificadores se discriminan por su tipo, no por su posición</h2>
 *
 * <p>{@code PID-3} es una lista y el orden no significa nada. Lo que dice qué es cada uno es
 * {@code PID-3.5}, el código de la tabla 0203, y da la casualidad —que no lo es— de que sus valores
 * corresponden uno a uno con los <em>slices</em> del perfil {@code paciente-lab-es}: {@code MR} es el
 * NHC, {@code NI} el DNI/NIE, {@code JHN} el CIP autonómico, {@code HC} el CIP-SNS y {@code SS} el
 * número de afiliación.
 */
@Component
public class TransformadorAdtAPaciente {

    /** Tabla 0203 → los {@code system} del laboratorio. */
    private static final Map<String, String> SISTEMA_POR_TIPO = Map.of(
            "MR", SistemasDeIdentificador.NHC,
            "NI", SistemasDeIdentificador.DNI_NIE,
            "JHN", SistemasDeIdentificador.CIP_AUTONOMICO,
            "HC", SistemasDeIdentificador.CIP_SNS,
            "SS", SistemasDeIdentificador.NASS);

    /**
     * Construye el recurso que se enviará al laboratorio.
     *
     * @param pid el segmento de demografía del mensaje
     * @return el {@code Patient} listo para la API, sin id: lo asigna el laboratorio
     * @throws HL7Exception si el segmento no se puede recorrer
     * @throws DemografiaIncompleta si falta lo que el laboratorio exige
     */
    public Patient aPatient(PID pid) throws HL7Exception {
        Map<String, String> identificadores = identificadoresDe(pid);
        String nhc = identificadores.get(SistemasDeIdentificador.NHC);
        if (nhc == null || nhc.isBlank()) {
            throw new DemografiaIncompleta("El PID no trae número de historia clínica (PID-3 con tipo «MR»), "
                    + "que es el único identificador que este laboratorio exige.");
        }

        Patient paciente = new Patient();
        paciente.getMeta().addProfile(SistemasDeIdentificador.PERFIL_PACIENTE);
        identificadores.forEach(
                (sistema, valor) -> paciente.addIdentifier().setSystem(sistema).setValue(valor));
        paciente.addName(nombreDe(pid.getPatientName(0)));
        paciente.setGender(sexoDe(valor(pid.getAdministrativeSex().getValue())));
        fechaDeNacimientoDe(pid).ifPresent(fecha -> paciente.setBirthDateElement(new DateType(fecha)));
        paciente.setActive(true);
        return paciente;
    }

    /** El NHC del mensaje, para indexar el original en el almacén antes de transformarlo. */
    public Optional<String> nhcDe(PID pid) {
        return Optional.ofNullable(identificadoresDe(pid).get(SistemasDeIdentificador.NHC));
    }

    private static Map<String, String> identificadoresDe(PID pid) {
        // `LinkedHashMap` y no `Map.of`: el orden de PID-3 se conserva en el recurso, que es lo que
        // hace que dos ejecuciones sobre el mismo mensaje produzcan exactamente el mismo JSON.
        Map<String, String> encontrados = new LinkedHashMap<>();
        for (CX identificador : pid.getPatientIdentifierList()) {
            String tipo = valor(identificador.getIdentifierTypeCode().getValue());
            String valor = valor(identificador.getIDNumber().getValue());
            String sistema = SISTEMA_POR_TIPO.get(tipo.toUpperCase());
            if (sistema != null && !valor.isBlank()) {
                encontrados.putIfAbsent(sistema, valor);
            }
        }
        return encontrados;
    }

    /**
     * {@code PID-5} → {@code HumanName}, con el apellido entero.
     *
     * <p>{@code XPN.1} es un {@code FN} y su primer componente es el nombre familiar completo. Los
     * demás componentes de {@code FN} describen apellidos de cónyuge, que no es lo que aquí hace
     * falta, así que no se tocan: ver la nota de la clase.
     */
    private static HumanName nombreDe(XPN xpn) {
        HumanName nombre = new HumanName().setUse(HumanName.NameUse.OFFICIAL);
        nombre.setFamily(valor(xpn.getFamilyName().getSurname().getValue()));

        String pila = valor(xpn.getGivenName().getValue());
        String segundo =
                valor(xpn.getSecondAndFurtherGivenNamesOrInitialsThereof().getValue());
        if (!pila.isBlank()) {
            nombre.addGiven(pila);
        }
        if (!segundo.isBlank()) {
            nombre.addGiven(segundo);
        }
        return nombre;
    }

    /**
     * {@code PID-7} → {@code birthDate}.
     *
     * <p>El tipo de V2 admite hasta el segundo y FHIR quiere una fecha, así que se toman los ocho
     * primeros dígitos. Si vienen menos —hay HIS que mandan solo el año— o no son una fecha, se
     * descarta: una fecha de nacimiento a medias inventaría un día que decide en qué rango de
     * referencia cae un resultado, y una imposible tumba el mapeo entero.
     *
     * <p>La conversión la hace {@link Campos#fechaIso}, que es donde vive esa regla. Este método tuvo
     * su propia copia hasta el 2026-08-15 y la copia era peor: no comprobaba que los ocho caracteres
     * fueran dígitos.
     */
    private static Optional<String> fechaDeNacimientoDe(PID pid) {
        return Campos.fechaIso(valor(pid.getDateTimeOfBirth().getTime().getValue()));
    }

    /** {@code PID-8}, tabla 0001. Lo que no se reconoce es {@code unknown}, no un error. */
    private static AdministrativeGender sexoDe(String codigo) {
        return switch (codigo.toUpperCase()) {
            case "F" -> AdministrativeGender.FEMALE;
            case "M" -> AdministrativeGender.MALE;
            case "A", "O" -> AdministrativeGender.OTHER;
            default -> AdministrativeGender.UNKNOWN;
        };
    }

    private static String valor(String posiblementeNulo) {
        return posiblementeNulo == null ? "" : posiblementeNulo.strip();
    }

    /** Falta en el mensaje algo sin lo cual el laboratorio no puede registrar al paciente. */
    public static final class DemografiaIncompleta extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DemografiaIncompleta(String mensaje) {
            super(mensaje);
        }
    }
}
