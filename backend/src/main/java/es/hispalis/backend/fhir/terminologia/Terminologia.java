package es.hispalis.backend.fhir.terminologia;

import es.hispalis.backend.dominio.edo.CatalogoEdo;
import es.hispalis.backend.dominio.resultado.ReglasReflejas;
import es.hispalis.backend.dominio.resultado.ValoresCriticos;
import org.hl7.fhir.r5.model.CodeableConcept;

/**
 * Lo que el laboratorio le pregunta al servidor de terminología (D14).
 *
 * <p><strong>Es un puerto, y su implementación habla FHIR estándar y nada más.</strong> Lo que hay
 * detrás es hoy un HAPI y mañana puede ser Snowstorm u Ontoserver: mientras se pregunte con
 * {@code $lookup}, {@code $validate-code} y {@code $translate}, cambiar de servidor es cambiar una
 * URL. En el momento en que algo de aquí dependa de una particularidad de HAPI, D14 deja de ser
 * cierta.
 *
 * <p>Deliberadamente <strong>no</strong> hay un método que devuelva «el catálogo entero». Con uno,
 * lo primero que haría alguien es guardárselo en un {@code Map<String,String>} al arrancar, que es
 * exactamente la lista paralela que prohíbe el invariante 4. Se pregunta por un código a la vez.
 *
 * <p><strong>Extiende {@link ValoresCriticos} y {@link ReglasReflejas}, que son puertos del dominio,
 * y la dirección importa: el borde depende del núcleo y no al revés.</strong> Los umbrales críticos y
 * las reglas reflejas los publica la misma autoridad que los nombres de las pruebas —son propiedades
 * del mismo concepto del catálogo—, así que los contesta el mismo cliente. Pero al dominio se le da
 * solo la pregunta estrecha: lo que la regla de la doble validación necesita saber es si una cifra
 * obliga a avisar, no qué es un {@code CodeableConcept}.
 */
public interface Terminologia extends ValoresCriticos, ReglasReflejas, CatalogoEdo {

    /**
     * El concepto codificado con el que se publica el valor de una prueba cualitativa.
     *
     * <p>Es el mismo {@code $lookup} que {@link #pruebaDelCatalogo}, contra otro {@code CodeSystem}.
     * Existe porque un resultado publicado como {@code POS} a secas no lo entiende nadie: la web y
     * la app leen {@code text} o {@code display}, y sin ellos enseñarían el hueco. El código es el
     * dato y el nombre es su presentación, pero un informe sin presentación no es un informe.
     *
     * <p>Nunca falla, como aquel: sin servidor devuelve el código a secas.
     *
     * @param codigoLocal {@code POS}, {@code NEG}, {@code IND}…
     */
    CodeableConcept valorCualitativo(String codigoLocal);

    /**
     * El concepto codificado con el que se publica una prueba del catálogo.
     *
     * <p>Devuelve el código local <strong>con su nombre en español</strong> —que es lo que se lee en
     * un informe (D7)— y, cuando el {@code ConceptMap} lo traduce, también su LOINC, para que quien
     * reciba el recurso pueda entenderlo sin conocer el dialecto de este laboratorio.
     *
     * <p>Nunca falla: si el servidor no contesta, devuelve el código a secas. El código es el dato y
     * el nombre es su presentación; dejar de publicar un resultado porque no se puede adornar sería
     * cambiar un problema de terminología por uno clínico.
     *
     * @param codigoLocal el código del catálogo del laboratorio ({@code GLU}, {@code TSH}…)
     */
    CodeableConcept pruebaDelCatalogo(String codigoLocal);

    /**
     * Comprueba contra el conjunto publicado que la prueba pedida existe.
     *
     * <p>La comprobación la hace el servidor con {@code $validate-code}, no una lista de aquí.
     *
     * <p>Que una prueba no esté <strong>no es un dato mal formado</strong>: el recurso es correcto y
     * el código está bien escrito, lo que pasa es que el laboratorio no oferta ese análisis. Por eso
     * sale {@code 422} y no {@code 400} — es la regla de negocio del catálogo, y es también el código
     * que FHIR reserva para un recurso que incumple el perfil al que se declara conforme.
     *
     * @param codigoLocal el código que llegó en el recurso
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si el servidor responde que no está
     */
    void exigirQueLaPruebaExiste(String codigoLocal);
}
