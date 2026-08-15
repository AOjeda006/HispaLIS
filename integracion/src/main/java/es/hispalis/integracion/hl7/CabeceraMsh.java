package es.hispalis.integracion.hl7;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import java.util.Optional;

/**
 * Lo que dice la cabecera del mensaje: de dónde viene, qué es y cómo se identifica.
 *
 * <p>Se extrae <strong>una sola vez</strong>, nada más recibirlo, porque de aquí salen las tres
 * cosas que el motor necesita antes de mirar el contenido: la clave de deduplicación, los metadatos
 * con los que el mensaje será localizable en el almacén, y el juego de caracteres.
 *
 * @param aplicacionEmisora {@code MSH-3}
 * @param instalacionEmisora {@code MSH-4}
 * @param tipo {@code MSH-9-1} — {@code ADT}, {@code OML}, {@code ORU}…
 * @param evento {@code MSH-9-2} — {@code A01}, {@code A08}…
 * @param estructura {@code MSH-9-3} — el código de la tabla 0354; puede no venir
 * @param controlId {@code MSH-10}
 * @param version {@code MSH-12}
 * @param charset lo declarado en {@code MSH-18}, ya resuelto
 */
public record CabeceraMsh(
        String aplicacionEmisora,
        String instalacionEmisora,
        String tipo,
        String evento,
        String estructura,
        String controlId,
        String version,
        CharsetDeclarado charset) {

    /**
     * Lee la cabecera del mensaje ya parseado.
     *
     * @throws HL7Exception si el {@code MSH} no se puede recorrer
     * @throws CharsetDeclarado.CharsetNoSoportado si {@code MSH-18} declara algo ilegible
     */
    public static CabeceraMsh de(Message mensaje) throws HL7Exception {
        Terser terser = new Terser(mensaje);
        return new CabeceraMsh(
                campo(terser, "MSH-3-1"),
                campo(terser, "MSH-4-1"),
                campo(terser, "MSH-9-1"),
                campo(terser, "MSH-9-2"),
                campo(terser, "MSH-9-3"),
                campo(terser, "MSH-10"),
                campo(terser, "MSH-12-1"),
                CharsetDeclarado.de(terser.get("MSH-18")));
    }

    /** {@code ADT^A01}, para mensajes y para el filtro del canal. */
    public String tipoYEvento() {
        return "%s^%s".formatted(tipo, evento);
    }

    public Optional<String> estructuraDeclarada() {
        return estructura == null || estructura.isBlank() ? Optional.empty() : Optional.of(estructura);
    }

    private static String campo(Terser terser, String ruta) throws HL7Exception {
        String valor = terser.get(ruta);
        return valor == null ? "" : valor.strip();
    }
}
