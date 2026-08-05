package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import java.time.Instant;
import java.util.Optional;

/**
 * Cuándo se hizo una determinación y quién la hizo.
 *
 * <p>Los dos datos viajan juntos porque describen el mismo hecho —el acto de medir— y separados
 * pierden la mitad del sentido: una cifra fechada sin autor no se puede reclamar, y un autor sin
 * fecha no dice si el dato es de esta mañana o del mes pasado.
 *
 * <p><strong>Los dos son opcionales, y no por comodidad:</strong> el perfil los declara {@code 0..1}
 * y {@code 0..*} respectivamente. Rechazar un resultado que la propia guía admite sería que el
 * servidor contradijera a su especificación. Lo que sí se exige es que, cuando lleguen, se guarden y
 * se devuelvan — que es lo que significa {@code Must Support} y lo que este agregado hace posible.
 *
 * <p>Lo que <strong>no</strong> se hace es inventarlos. Rellenar la fecha con {@code now()} cuando el
 * cliente no la manda produce un dato con toda la apariencia de ser bueno y que además ordena mal la
 * historia del paciente: la hora de registro no es la hora de la medición, y confundirlas coloca un
 * resultado de ayer entre los de hoy.
 */
public final class Medicion {

    private static final Medicion SIN_CONSTANCIA = new Medicion(null, null);

    private final Instant realizadaEn;
    private final String realizadaPor;

    private Medicion(Instant realizadaEn, String realizadaPor) {
        this.realizadaEn = realizadaEn;
        this.realizadaPor = realizadaPor;
    }

    /** La medición de la que no consta ni cuándo ni quién. */
    public static Medicion sinConstancia() {
        return SIN_CONSTANCIA;
    }

    /**
     * Registra los datos de una medición.
     *
     * @param realizadaEn cuándo se midió, o {@code null} si no consta
     * @param realizadaPor referencia a quien la hizo ({@code Organization/…} o
     *     {@code Practitioner/…}), o {@code null} si no consta. Es una cadena por coherencia con
     *     {@link es.hispalis.backend.dominio.informe.Informe#emisor()}: ni el laboratorio ni el
     *     facultativo son agregados de este dominio, son dato maestro.
     * @throws DatoInvalido si la fecha está en el futuro
     */
    public static Medicion de(Instant realizadaEn, String realizadaPor) {
        // Una determinación fechada mañana no es un caso raro que ya se corregirá: es un reloj mal
        // puesto en un analizador, y su efecto es que el resultado se cuela al principio de la
        // historia del paciente y se lee como si fuese el más reciente.
        if (realizadaEn != null && realizadaEn.isAfter(Instant.now())) {
            throw new DatoInvalido("La fecha de medición %s está en el futuro: revisa el reloj del analizador."
                    .formatted(realizadaEn));
        }

        String autor = realizadaPor == null || realizadaPor.isBlank() ? null : realizadaPor.strip();
        return realizadaEn == null && autor == null ? SIN_CONSTANCIA : new Medicion(realizadaEn, autor);
    }

    public Optional<Instant> realizadaEn() {
        return Optional.ofNullable(realizadaEn);
    }

    public Optional<String> realizadaPor() {
        return Optional.ofNullable(realizadaPor);
    }
}
