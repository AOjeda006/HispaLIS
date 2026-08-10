package es.hispalis.backend.fhir;

import java.util.Optional;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;

/**
 * Los valores con los que este laboratorio informa una prueba cualitativa.
 *
 * <p>El hermano de {@link CatalogoDePruebas} para el otro lado del recurso: allí el {@code system}
 * del <em>qué se midió</em>, aquí el del <em>qué salió</em>. Y por lo mismo, esta clase tampoco
 * conoce ningún código concreto: no hay una lista de {@code POS}, {@code NEG}… escrita en Java.
 * Cuáles hay lo publica la guía; lo único que se sabe aquí es cómo reconocerlos.
 *
 * <p>Importa que sean códigos y no texto porque de este valor depende que se declare o no una
 * enfermedad a Salud Pública. Con «Positivo» en texto libre, esa decisión sería una comparación de
 * cadenas.
 */
public final class ResultadosCualitativos {

    /** El {@code system} del vocabulario, tal y como lo publica la guía. */
    public static final String SYSTEM = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/resultados-cualitativos";

    private ResultadosCualitativos() {
        // Utilidad.
    }

    /**
     * Extrae el código cualitativo local de un concepto codificado.
     *
     * <p>Se filtra por {@code system} y, si no hay ninguno de este laboratorio, se acepta el primer
     * {@code Coding} que traiga código. La diferencia con {@link CatalogoDePruebas#codigoDe} es
     * deliberada: allí un código ajeno sería un LOINC que se colaría donde va el del catálogo, y aquí
     * un código ajeno es un resultado legítimo que el laboratorio no ha codificado en su dialecto —un
     * grupo sanguíneo, un serotipo—. Guardarlo es mejor que perderlo; no disparará ninguna regla EDO,
     * que es lo que se quiere.
     *
     * @param concepto el concepto tal y como llegó, o {@code null}
     */
    public static Optional<String> codigoDe(CodeableConcept concepto) {
        if (concepto == null) {
            return Optional.empty();
        }
        return concepto.getCoding().stream()
                .filter(codigo -> codigo.getCode() != null && !codigo.getCode().isBlank())
                .sorted((uno, otro) ->
                        Boolean.compare(!SYSTEM.equals(uno.getSystem()), !SYSTEM.equals(otro.getSystem())))
                .map(Coding::getCode)
                .findFirst();
    }
}
