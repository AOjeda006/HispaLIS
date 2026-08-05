package es.hispalis.backend.fhir;

/**
 * Los {@code Identifier.system} que usa el laboratorio.
 *
 * <p>Un identificador sin {@code system} no identifica nada: «12345678» puede ser un NHC, un DNI sin
 * letra o un número de afiliación. El {@code system} es lo que dice de qué registro procede, y por
 * eso ninguna parte del código escribe una de estas URI a mano.
 *
 * <p>Dos vienen del <strong>Ministerio de Sanidad</strong> —son los OID que la guía española de
 * ÚNICAS usa para el DNI/NIE y el CIP-SNS, y se adoptan en vez de inventar unos propios (D21)—. Los
 * demás son <strong>propios de esta simulación y no oficiales</strong> (D19, §4.8): se definen
 * porque no existe alternativa publicada, y así queda escrito en la portada de la guía.
 *
 * <p>Igual que {@link PerfilesDeLaGuia}, esta lista repite lo que vive en
 * {@code ig/input/fsh/aliases.fsh}, y hay un test que las cruza y falla si divergen.
 */
public final class SistemasDeIdentificador {

    /** Número de historia clínica del laboratorio. Propio: lo emitimos nosotros. */
    public static final String NHC = "https://aojeda006.github.io/HispaLIS/sid/nhc";

    /** DNI o NIE. OID del registro español, adoptado del Ministerio (D21). */
    public static final String DNI_NIE = "urn:oid:1.3.6.1.4.1.19126.3";

    /** CIP autonómico; en Andalucía, el NUHSA. Sin OID oficial publicado, así que propio. */
    public static final String CIP_AUTONOMICO = "https://aojeda006.github.io/HispaLIS/sid/nuhsa";

    /** Código de identificación personal del SNS. OID adoptado del Ministerio (D21). */
    public static final String CIP_SNS = "urn:oid:2.16.724.4.40";

    /** Número de afiliación a la Seguridad Social. Propio. */
    public static final String NASS = "https://aojeda006.github.io/HispaLIS/sid/nass";

    private SistemasDeIdentificador() {
        // Solo constantes.
    }
}
