package es.hispalis.backend.fhir.notificacion;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.backend.TestDeIntegracion;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.SubscriptionTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El disparador vive en el {@code SubscriptionTopic}, y esto comprueba que se evalúa de verdad.
 *
 * <p>El tópico que se usa es <strong>el publicado</strong>, leído del propio servidor: si mañana
 * alguien cambia el criterio en el FSH y el fichero de conformidad, este test cambia de resultado
 * sin tocar una línea de Java. Eso es exactamente lo que se quería al sacar el criterio del código —
 * y un test que se escribiera su propio tópico no lo demostraría.
 */
class CriterioDelTopicoTest extends TestDeIntegracion {

    @Autowired
    private CriterioDelTopico criterio;

    @Autowired
    private TopicosDelLaboratorio topicos;

    @Test
    @DisplayName("pasar de `preliminary` a `final` dispara")
    void elCambioAFinalDispara() {
        assertThat(criterio.dispara(
                        topico(), resultado(ObservationStatus.PRELIMINARY), resultado(ObservationStatus.FINAL)))
                .isTrue();
    }

    @Test
    @DisplayName("nacer ya en `final` dispara: el motor de integración recibe resultados ya firmados")
    void nacerEnFinalDispara() {
        assertThat(criterio.dispara(topico(), null, resultado(ObservationStatus.FINAL)))
                .isTrue();
    }

    @Test
    @DisplayName("reescribir algo que YA estaba en `final` no vuelve a disparar")
    void reescribirUnFinalNoDispara() {
        assertThat(criterio.dispara(topico(), resultado(ObservationStatus.FINAL), resultado(ObservationStatus.FINAL)))
                .isFalse();
    }

    @Test
    @DisplayName("un resultado que sigue preliminar no dispara: `final` es lo que significa «responde alguien»")
    void loPreliminarNoDispara() {
        assertThat(criterio.dispara(
                        topico(), resultado(ObservationStatus.REGISTERED), resultado(ObservationStatus.PRELIMINARY)))
                .isFalse();
    }

    @Test
    @DisplayName("otro tipo de recurso no dispara aunque cambie de estado")
    void otroRecursoNoDispara() {
        assertThat(criterio.dispara(topico(), null, new Patient())).isFalse();
    }

    private SubscriptionTopic topico() {
        return topicos.publicados().stream()
                .filter(publicado -> publicado.getUrl().endsWith("/SubscriptionTopic/resultado-validado"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("el laboratorio no ha publicado su tópico al arrancar"));
    }

    private static Observation resultado(ObservationStatus estado) {
        Observation resultado = new Observation();
        resultado.setId("Observation/da39a3ee-5e6b-4b0d-3255-bfef95601890");
        resultado.setStatus(estado);
        return resultado;
    }
}
