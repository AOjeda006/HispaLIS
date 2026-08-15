package es.hispalis.integracion.hl7;

import ca.uhn.hl7v2.model.Primitive;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Lectura defensiva de campos v2.
 *
 * <p>Existe por una razón concreta: en HAPI, un campo que no viene <strong>no es</strong> una cadena
 * vacía. Puede ser {@code null}, puede ser un objeto cuyo {@code getValue()} devuelve {@code null}, y
 * puede traer espacios de relleno del emisor. Repartir ese {@code if} por cada transformador es cómo
 * aparecen los {@code NullPointerException} en producción, en el campo que nadie mandaba nunca hasta
 * que un emisor nuevo empezó a mandarlo vacío.
 */
public final class Campos {

    /** La zona del laboratorio. Un {@code TS} de v2 sin desplazamiento es hora local del emisor. */
    private static final ZoneId ZONA = ZoneId.of("Europe/Madrid");

    private Campos() {
        // Utilidad.
    }

    /** El valor del campo, o cadena vacía. Nunca {@code null}. */
    public static String texto(Primitive campo) {
        if (campo == null || campo.getValue() == null) {
            return "";
        }
        return campo.getValue().strip();
    }

    /** Igual, pero diciendo si había algo. */
    public static Optional<String> opcional(Primitive campo) {
        String valor = texto(campo);
        return valor.isBlank() ? Optional.empty() : Optional.of(valor);
    }

    /**
     * Convierte un {@code TS} de v2 en un instante.
     *
     * <p>v2 admite precisión variable en el mismo campo —{@code 20260806}, {@code 202608061230},
     * {@code 20260806123045}— y los tres son legales. Se toma lo que venga y se completa con ceros;
     * lo que no se hace es rechazar el mensaje por una precisión que el estándar permite.
     *
     * @return el instante, o vacío si el campo no trae una fecha reconocible
     */
    public static Optional<Instant> instante(String valorV2) {
        if (valorV2 == null) {
            return Optional.empty();
        }
        // El desplazamiento horario, si viene, va tras un `+` o un `-`; aquí se descarta y se usa la
        // zona del laboratorio. Guardar la hora local del emisor sin su desplazamiento sería peor.
        String digitos = valorV2.split("[+-]", 2)[0].strip();
        if (digitos.length() < 8) {
            return Optional.empty();
        }
        try {
            LocalDate dia = LocalDate.of(
                    Integer.parseInt(digitos.substring(0, 4)),
                    Integer.parseInt(digitos.substring(4, 6)),
                    Integer.parseInt(digitos.substring(6, 8)));
            int hora = trozo(digitos, 8);
            int minuto = trozo(digitos, 10);
            int segundo = trozo(digitos, 12);
            return Optional.of(LocalDateTime.of(dia, java.time.LocalTime.of(hora, minuto, segundo))
                    .atZone(ZONA)
                    .toInstant());
        } catch (NumberFormatException | java.time.DateTimeException noEsUnaFecha) {
            return Optional.empty();
        }
    }

    /**
     * Los ocho primeros dígitos de un {@code TS}, que es la fecha en formato FHIR.
     *
     * <p>Comprueba que <strong>sean</strong> dígitos y que compongan un día que existe, y no solo que
     * haya ocho caracteres. Cortar por posición y confiar es lo que produce un {@code birthDate} como
     * {@code ABCD-EF-GH} o {@code 0000-00-00}: FHIR los rechaza al construir el tipo, la excepción
     * sale del mapeo y el mensaje acaba archivado como fallo del laboratorio cuando lo que pasa es
     * que el emisor manda una fecha que no lo es.
     *
     * @return la fecha {@code AAAA-MM-DD}, o vacío si no hay una fecha reconocible
     */
    public static Optional<String> fechaIso(String valorV2) {
        if (valorV2 == null || valorV2.strip().length() < 8) {
            return Optional.empty();
        }
        String digitos = valorV2.strip().substring(0, 8);
        if (!digitos.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(
                            Integer.parseInt(digitos.substring(0, 4)),
                            Integer.parseInt(digitos.substring(4, 6)),
                            Integer.parseInt(digitos.substring(6)))
                    .toString());
        } catch (java.time.DateTimeException noEsUnDiaQueExista) {
            return Optional.empty();
        }
    }

    private static int trozo(String digitos, int desde) {
        return digitos.length() >= desde + 2 ? Integer.parseInt(digitos.substring(desde, desde + 2)) : 0;
    }
}
