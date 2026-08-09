package es.hispalis.backend.dominio.resultado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.hispalis.backend.dominio.DatoInvalido;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La regla que separa «fuera de rango» de «hay que llamar por teléfono».
 *
 * <p>Aquí no hay servidor ni catálogo: se prueba la decisión, con los umbrales puestos a mano. Que
 * esos umbrales salgan de la guía y no de una lista escrita en el código lo prueba
 * {@code TerminologiaEnLaProyeccionTest}, contra un {@code $lookup} de verdad.
 */
class UmbralCriticoTest {

    private static final String FUENTE =
            "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182).";

    private static UmbralCritico potasio() {
        return new UmbralCritico("K", new BigDecimal("2.8"), new BigDecimal("6.3"), "mmol/L", FUENTE);
    }

    @Test
    @DisplayName("6,2 está fuera de rango y no es crítico; 7,5 sí lo es")
    void elCriterio() {
        assertThat(potasio().alcanzaA(new BigDecimal("6.2"), "mmol/L")).isFalse();
        assertThat(potasio().alcanzaA(new BigDecimal("7.5"), "mmol/L")).isTrue();
    }

    @Test
    @DisplayName("los dos extremos cuentan, y el límite exacto avisa")
    void losDosExtremos() {
        assertThat(potasio().alcanzaA(new BigDecimal("2.5"), "mmol/L")).isTrue();
        assertThat(potasio().alcanzaA(new BigDecimal("2.8"), "mmol/L")).isTrue();
        assertThat(potasio().alcanzaA(new BigDecimal("6.3"), "mmol/L")).isTrue();
        assertThat(potasio().alcanzaA(new BigDecimal("4.1"), "mmol/L")).isFalse();
    }

    @Test
    @DisplayName("un solo límite basta: la creatinina no tiene crítico por lo bajo")
    void unSoloLimiteBasta() {
        UmbralCritico creatinina = new UmbralCritico("CREA", null, new BigDecimal("5"), "mg/dL", FUENTE);

        assertThat(creatinina.alcanzaA(new BigDecimal("7.2"), "mg/dL")).isTrue();
        // Y por lo bajo no dispara nada, porque la fuente no declara ningún límite inferior. Que
        // falte no es un descuido que haya que rellenar con criterio propio.
        assertThat(creatinina.alcanzaA(new BigDecimal("0.1"), "mg/dL")).isFalse();
        assertThat(creatinina.limiteBajo()).isEmpty();
    }

    @Test
    @DisplayName("en otra unidad no se compara: se dice que no se sabe")
    void enOtraUnidadNoSeCompara() {
        assertThatThrownBy(() -> potasio().alcanzaA(new BigDecimal("7.5"), "mg/dL"))
                .isInstanceOf(NoSeSabeSiEsCritico.class)
                .hasMessageContaining("mmol/L");
    }

    @Test
    @DisplayName("un resultado sin cifra no es crítico por umbral, porque no hay nada que comparar")
    void sinCifraNoHayUmbralQueAlcanzar() {
        assertThat(potasio().alcanzaA(null, "mmol/L")).isFalse();
    }

    @Test
    @DisplayName("un umbral sin procedencia no se puede ni construir")
    void sinProcedenciaNoSeConstruye() {
        // Es la regla del ítem 43 puesta en el tipo: no hay forma de tener un umbral en memoria sin
        // poder decir de dónde salió su cifra.
        assertThatThrownBy(() -> new UmbralCritico("K", null, new BigDecimal("6.3"), "mmol/L", "  "))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("de dónde sale");
    }

    @Test
    @DisplayName("y tampoco sin unidad, sin ningún límite, ni con los límites del revés")
    void losDemasInvariantes() {
        assertThatThrownBy(() -> new UmbralCritico("K", null, new BigDecimal("6.3"), null, FUENTE))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("en qué unidad");
        assertThatThrownBy(() -> new UmbralCritico("K", null, null, "mmol/L", FUENTE))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("ningún límite");
        assertThatThrownBy(() -> new UmbralCritico("K", new BigDecimal("6.3"), new BigDecimal("2.8"), "mmol/L", FUENTE))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("no puede ser mayor");
    }
}
