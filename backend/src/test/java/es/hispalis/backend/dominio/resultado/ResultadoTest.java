package es.hispalis.backend.dominio.resultado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.EstadoDeEspecimen;
import es.hispalis.backend.dominio.especimen.NumeroDeAcceso;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La validación facultativa, probada <strong>en el núcleo</strong>: sin Spring, sin HTTP y sin base
 * de datos.
 *
 * <p>{@code ValidacionFacultativaTest} comprueba lo mismo por la API, y hace falta —es lo que ve el
 * cliente—, pero solo este demuestra <strong>dónde vive la regla</strong>. Si hubiera que levantar el
 * servidor para probarla, estaría en la puerta, y dejaría de aplicarse en cuanto apareciera otra: el
 * hito 2 trae una, el motor de integración.
 */
class ResultadoTest {

    private static final String FACULTATIVA = "Practitioner/analisis-clinicos";
    private static final String OTRA = "Practitioner/otra";

    @Test
    void un_resultado_recien_informado_no_esta_validado() {
        Resultado glucosa = informar();

        assertThat(glucosa.estaValidado()).isFalse();
        assertThat(glucosa.estado()).isEqualTo(EstadoDeResultado.PRELIMINAR);
        assertThat(glucosa.estado().esPublicable())
                .as("lo que sale del analizador es una cifra medida, no un resultado publicable")
                .isFalse();
    }

    @Test
    void validar_deja_constancia_de_quien_firma_y_de_cuando() {
        Instant cuando = Instant.now().minus(Duration.ofMinutes(5));

        Resultado firmado = informar().validar(FACULTATIVA, cuando);

        assertThat(firmado.estado()).isEqualTo(EstadoDeResultado.VALIDADO);
        assertThat(firmado.validacion().orElseThrow().facultativo()).isEqualTo(FACULTATIVA);
        assertThat(firmado.validacion().orElseThrow().realizadaEn()).isEqualTo(cuando);
    }

    /** Validar es responder de una cifra, no reescribirla. Si el valor cambiara, sería otra operación. */
    @Test
    void validar_no_toca_lo_medido_ni_el_resultado_de_partida() {
        Resultado medido = informar();

        Resultado firmado = medido.validar(FACULTATIVA, null);

        assertThat(firmado.id()).isEqualTo(medido.id());
        assertThat(firmado.valor()).isEqualTo(medido.valor());
        assertThat(medido.estaValidado())
                .as("el agregado de partida no se muta: la firma produce uno nuevo")
                .isFalse();
    }

    /**
     * Revalidar no es corregir. Si valiera, la segunda firma taparía a la primera y el rastro de
     * quién respondió del resultado quedaría reescrito sin dejar constancia de que hubo otro.
     */
    @Test
    void un_resultado_ya_validado_no_se_vuelve_a_validar() {
        Resultado firmado = informar().validar(FACULTATIVA, null);

        assertThatThrownBy(() -> firmado.validar(OTRA, null))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .as("hay que decir quién firmó ya, no solo que estaba firmado")
                .hasMessageContaining(FACULTATIVA);
    }

    @Test
    void una_firma_sin_firmante_no_es_una_firma() {
        Resultado medido = informar();

        assertThatThrownBy(() -> medido.validar("  ", null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("quién");
    }

    /** Una firma con fecha futura no se puede contrastar con los controles de calidad de ese día. */
    @Test
    void una_firma_no_se_puede_fechar_en_el_futuro() {
        Resultado medido = informar();
        Instant manana = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> medido.validar(FACULTATIVA, manana)).isInstanceOf(DatoInvalido.class);
    }

    @Test
    void sin_fecha_se_firma_ahora() {
        Instant antes = Instant.now().minusSeconds(1);

        Resultado firmado = informar().validar(FACULTATIVA, null);

        assertThat(firmado.validacion().orElseThrow().realizadaEn()).isAfter(antes);
    }

    private static Resultado informar() {
        Especimen muestra = Especimen.registrar(
                new NumeroDeAcceso("A" + UUID.randomUUID()),
                UUID.randomUUID(),
                "122555007",
                EstadoDeEspecimen.DISPONIBLE,
                null);
        return Resultado.informarCuantitativo(
                muestra, null, "GLU", new BigDecimal("92"), "mg/dL", Medicion.sinConstancia());
    }
}
