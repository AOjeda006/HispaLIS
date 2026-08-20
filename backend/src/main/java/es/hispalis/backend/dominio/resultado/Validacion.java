package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.MarcaDeTiempo;
import java.time.Instant;

/**
 * La firma facultativa de un resultado: quién responde de la cifra y desde cuándo.
 *
 * <p>Los dos datos viajan juntos por lo mismo que en {@link Medicion}: describen un solo acto. Una
 * validación sin autor no se puede reclamar y un autor sin fecha no dice si la revisión es anterior
 * o posterior al control de calidad del día.
 *
 * <p>A diferencia de {@link Medicion}, aquí <strong>nada es opcional</strong>. La medición puede no
 * constar porque el perfil la declara opcional y rechazarla sería contradecir a la propia guía; la
 * validación, en cambio, no «llega» de ninguna parte: se produce cuando alguien la hace, y si no
 * consta quién la hizo es que no se ha hecho.
 */
public final class Validacion {

    private final String facultativo;
    private final Instant realizadaEn;

    private Validacion(String facultativo, Instant realizadaEn) {
        this.facultativo = facultativo;
        this.realizadaEn = realizadaEn;
    }

    /**
     * @param facultativo referencia a quien valida ({@code Practitioner/…}), como cadena opaca — el
     *     facultativo es dato maestro del laboratorio, no un agregado de este dominio
     * @param realizadaEn cuándo se valida; {@code null} se resuelve como ahora
     * @throws DatoInvalido si no se dice quién valida, o si la fecha está en el futuro
     */
    public static Validacion por(String facultativo, Instant realizadaEn) {
        if (facultativo == null || facultativo.isBlank()) {
            throw new DatoInvalido(
                    "Un resultado lo valida una persona y hay que decir quién: de eso depende quién responde "
                            + "de la cifra cuando alguien pregunte.");
        }
        // Por la misma razón que en la medición: una firma fechada mañana es un reloj mal puesto, y
        // su efecto es que la revisión parece más reciente de lo que fue.
        if (realizadaEn != null && realizadaEn.isAfter(Instant.now())) {
            throw new DatoInvalido("La fecha de validación %s está en el futuro.".formatted(realizadaEn));
        }
        // En milisegundos, por lo mismo que la emisión del informe: ver MarcaDeTiempo. La firma se
        // publica como `Provenance.recorded`, que es un `instant` de FHIR.
        return new Validacion(facultativo.strip(), MarcaDeTiempo.publicable(realizadaEn));
    }

    public String facultativo() {
        return facultativo;
    }

    public Instant realizadaEn() {
        return realizadaEn;
    }
}
