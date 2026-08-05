package es.hispalis.backend.fhir;

import java.util.Optional;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;

/**
 * El catálogo local de pruebas del laboratorio: el dialecto con el que pide y firma sus análisis.
 *
 * <p>Es el <strong>lado nuestro</strong> del mapeo. La traducción a LOINC la hace el
 * {@code ConceptMap} de la guía y no este código: un {@code Map<String,String>} de códigos aquí
 * sería exactamente la lista paralela que el proyecto prohíbe.
 *
 * <p>Por eso esta clase no conoce ningún código concreto — no hay una lista de {@code GLU},
 * {@code TSH}…—: solo sabe reconocer cuáles son del catálogo y cuáles no.
 */
public final class CatalogoDePruebas {

    /** El {@code system} del catálogo, tal y como lo publica la guía. */
    public static final String SYSTEM = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas";

    private CatalogoDePruebas() {
        // Utilidad.
    }

    /**
     * Extrae el código del catálogo local de un concepto codificado.
     *
     * <p>Se filtra por {@code system} y no se coge el primer {@code Coding} que aparezca: un
     * {@code CodeableConcept} puede traer el mismo concepto en varias codificaciones a la vez —el
     * código local y su LOINC—, y confundirlos guardaría un LOINC donde el dominio espera su propio
     * código.
     *
     * @param concepto el concepto tal y como llegó, o {@code null}
     * @return el código del catálogo, si el concepto trae uno
     */
    public static Optional<String> codigoDe(CodeableConcept concepto) {
        if (concepto == null) {
            return Optional.empty();
        }
        return concepto.getCoding().stream()
                .filter(codigo -> SYSTEM.equals(codigo.getSystem()))
                .map(Coding::getCode)
                .filter(codigo -> codigo != null && !codigo.isBlank())
                .findFirst();
    }
}
