package es.hispalis.integracion.fhir;

/**
 * Los {@code Identifier.system} del laboratorio, vistos desde el motor.
 *
 * <p>Repite lo que declara {@code ig/input/fsh/aliases.fsh} —igual que el backend—, y por la misma
 * razón: el motor construye recursos FHIR y no puede depender de que alguien haya arrancado la guía.
 * Hay un test que cruza esta lista contra el FSH y falla en cuanto divergen, porque equivocar un
 * {@code system} <strong>no rompe nada visible</strong>: el recurso valida, se guarda, y el
 * identificador simplemente deja de significar lo que dice.
 */
public final class SistemasDeIdentificador {

    /** Número de historia clínica del laboratorio. El único que emite el propio centro. */
    public static final String NHC = "https://aojeda006.github.io/HispaLIS/sid/nhc";

    /** DNI o NIE. OID del registro español, adoptado del Ministerio (D21). */
    public static final String DNI_NIE = "urn:oid:1.3.6.1.4.1.19126.3";

    /** CIP autonómico; en Andalucía, el NUHSA. */
    public static final String CIP_AUTONOMICO = "https://aojeda006.github.io/HispaLIS/sid/nuhsa";

    /** Código de identificación personal del SNS. OID adoptado del Ministerio (D21). */
    public static final String CIP_SNS = "urn:oid:2.16.724.4.40";

    /** Número de afiliación a la Seguridad Social. */
    public static final String NASS = "https://aojeda006.github.io/HispaLIS/sid/nass";

    /** El perfil de paciente de la guía, que es lo que el motor declara producir. */
    public static final String PERFIL_PACIENTE =
            "https://aojeda006.github.io/HispaLIS/fhir/StructureDefinition/paciente-lab-es";

    private SistemasDeIdentificador() {
        // Solo constantes.
    }
}
