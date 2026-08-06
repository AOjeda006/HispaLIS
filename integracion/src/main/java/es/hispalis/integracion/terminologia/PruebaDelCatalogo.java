package es.hispalis.integracion.terminologia;

import java.util.Optional;

/**
 * Una prueba del catálogo local, con lo que el motor necesita saber de ella.
 *
 * <p>La unidad viene del catálogo y no del mensaje, y esa es una decisión de seguridad clínica: un
 * analizador mal configurado puede mandar la creatinina en {@code umol/L} donde el laboratorio la
 * informa en {@code mg/dL}, y las dos cifras son «correctas» por separado. Lo que se compara es la
 * unidad declarada en {@code OBX-6} contra <strong>esta</strong>, que es la que el laboratorio
 * publica; si no cuadran, el resultado no entra.
 *
 * @param codigo el código del catálogo local ({@code GLU}, {@code TSH}…)
 * @param display cómo se llama la prueba, en español, tal y como lo publica la guía
 * @param unidadUcum la unidad en que este laboratorio informa la prueba, o {@code null} si es cualitativa
 * @param loinc el LOINC equivalente según el {@code ConceptMap}, o {@code null} si no lo tiene
 */
public record PruebaDelCatalogo(String codigo, String display, String unidadUcum, String loinc) {

    /** Una prueba cuantitativa se informa con cifra y unidad; una cualitativa, con un concepto. */
    public boolean esCuantitativa() {
        return unidadUcum != null && !unidadUcum.isBlank();
    }

    public Optional<String> unidad() {
        return Optional.ofNullable(unidadUcum);
    }

    public Optional<String> codigoLoinc() {
        return Optional.ofNullable(loinc);
    }
}
