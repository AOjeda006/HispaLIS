package es.hispalis.backend.fhir.resultado;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import es.hispalis.backend.fhir.ResultadosCualitativos;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * El resultado que no se mide ni se codifica: el que llega como texto.
 *
 * <p>Lo señaló la cobertura, con un cero redondo sobre {@code Resultado.informarTextual} — una
 * fábrica del dominio a la que se llega <strong>por dos caminos</strong> desde la API y que no
 * recorría ningún test:
 *
 * <ol>
 *   <li>un {@code Observation.valueString}, que es como llega un comentario del facultativo o un
 *       resultado que no tiene número («muestra hemolizada»);
 *   <li>un {@code valueCodeableConcept} <strong>sin ningún código dentro</strong>, solo con
 *       {@code text}. Esta segunda es la caída que dejó `adr-0034`: el código manda sobre el texto, y
 *       cuando no hay código lo que queda ya no es un resultado codificado sino una descripción.
 * </ol>
 *
 * <p>La segunda importa más de lo que parece, y por eso se afirma su consecuencia y no solo su
 * almacenamiento: de un valor <em>codificado</em> depende que se declare una enfermedad a Salud
 * Pública. Un `{text: "Positivo"}` sin `coding` **no** es un positivo declarable, es una frase — y
 * confundirlos sería declarar por comparar cadenas, que es justo lo que el dominio no hace.
 */
class ResultadoTextualTest extends TestDeIntegracion {

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    private CircuitoDePrueba circuito;
    private String laboratorio;
    private String paciente;
    private String muestra;

    @BeforeEach
    void prepararLaMuestra() {
        circuito = new CircuitoDePrueba(rest, contexto);
        laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
    }

    @Test
    void un_resultado_de_texto_se_guarda_y_vuelve_como_texto() {
        Observation informado = sinValor("GLU");
        informado.setValue(new StringType("Muestra hemolizada; no se puede informar la cifra."));

        String referencia = circuito.crear(informado);

        Observation publicado = circuito.leer(referencia, Observation.class);
        assertThat(publicado.getValueStringType().getValue())
                .isEqualTo("Muestra hemolizada; no se puede informar la cifra.");
        assertThat(publicado.hasValueQuantity())
                .as("un texto no es una cifra, y publicarlo como tal inventaría un número")
                .isFalse();
    }

    /**
     * La caída de {@code adr-0034}, por el lado que no se probaba.
     *
     * <p>El test de aquella corrección comprueba que un {@code CodeableConcept} <em>con</em> código se
     * guarda codificado y que el {@code text} no le gana. Lo que faltaba es el caso contrario: sin
     * código, no hay nada que codificar y el laboratorio no debe inventárselo.
     */
    @Test
    void un_concepto_sin_codigo_se_guarda_como_descripcion_y_no_como_valor_codificado() {
        Observation informado = sinValor("GLU");
        informado.setValue(new CodeableConcept().setText("Positivo"));

        String referencia = circuito.crear(informado);

        Observation publicado = circuito.leer(referencia, Observation.class);
        assertThat(publicado.getValueStringType().getValue())
                .as("sin `coding` lo que hay es una frase, y como frase se guarda")
                .isEqualTo("Positivo");
        assertThat(publicado.hasValueCodeableConcept())
                .as("deducir `POS` de la palabra «Positivo» sería declarar una enfermedad comparando cadenas")
                .isFalse();
    }

    /** Y con código sí: el mismo camino, para que la diferencia se lea en un solo sitio. */
    @Test
    void el_mismo_concepto_con_codigo_si_se_guarda_codificado() {
        Observation informado = sinValor("GLU");
        informado.setValue(new CodeableConcept()
                .addCoding(new Coding().setSystem(ResultadosCualitativos.SYSTEM).setCode("POS"))
                .setText("Positivo"));

        String referencia = circuito.crear(informado);

        Observation publicado = circuito.leer(referencia, Observation.class);
        assertThat(publicado.getValueCodeableConcept().getCodingFirstRep().getCode())
                .isEqualTo("POS");
    }

    /** Un {@code Observation} del circuito al que se le quita la cifra para poner otra cosa. */
    private Observation sinValor(String prueba) {
        Observation resultado = CircuitoDePrueba.resultado(paciente, muestra, null, laboratorio);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(prueba)));
        resultado.setValue(null);
        return resultado;
    }
}
