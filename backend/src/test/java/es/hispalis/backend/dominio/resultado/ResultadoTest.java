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
import java.util.Optional;
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

    /** Una glucosa de 92 no tiene umbral declarado en este catálogo: una firma basta. */
    private static final ValoresCriticos SIN_UMBRALES = codigoDePrueba -> Optional.empty();

    /** El potasio del catálogo de la guía, con su procedencia. Un 6,9 alcanza el límite alto. */
    private static final ValoresCriticos CON_EL_POTASIO = codigoDePrueba -> Optional.of(new UmbralCritico(
            "K",
            new BigDecimal("2.8"),
            new BigDecimal("6.3"),
            "mmol/L",
            "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182)."));

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

        Resultado firmado = informar().validar(SIN_UMBRALES, FACULTATIVA, cuando);

        assertThat(firmado.estado()).isEqualTo(EstadoDeResultado.VALIDADO);
        assertThat(firmado.ultimaFirma().orElseThrow().facultativo()).isEqualTo(FACULTATIVA);
        assertThat(firmado.ultimaFirma().orElseThrow().realizadaEn()).isEqualTo(cuando);
    }

    /** Validar es responder de una cifra, no reescribirla. Si el valor cambiara, sería otra operación. */
    @Test
    void validar_no_toca_lo_medido_ni_el_resultado_de_partida() {
        Resultado medido = informar();

        Resultado firmado = medido.validar(SIN_UMBRALES, FACULTATIVA, null);

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
        Resultado firmado = informar().validar(SIN_UMBRALES, FACULTATIVA, null);

        assertThatThrownBy(() -> firmado.validar(SIN_UMBRALES, OTRA, null))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .as("hay que decir quién firmó ya, no solo que estaba firmado")
                .hasMessageContaining(FACULTATIVA);
    }

    @Test
    void una_firma_sin_firmante_no_es_una_firma() {
        Resultado medido = informar();

        assertThatThrownBy(() -> medido.validar(SIN_UMBRALES, "  ", null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("quién");
    }

    /** Una firma con fecha futura no se puede contrastar con los controles de calidad de ese día. */
    @Test
    void una_firma_no_se_puede_fechar_en_el_futuro() {
        Resultado medido = informar();
        Instant manana = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> medido.validar(SIN_UMBRALES, FACULTATIVA, manana))
                .isInstanceOf(DatoInvalido.class);
    }

    @Test
    void sin_fecha_se_firma_ahora() {
        Instant antes = Instant.now().minusSeconds(1);

        Resultado firmado = informar().validar(SIN_UMBRALES, FACULTATIVA, null);

        assertThat(firmado.ultimaFirma().orElseThrow().realizadaEn()).isAfter(antes);
    }

    // ─── La doble validación del crítico (ítem 46, §10) ─────────────────────

    /**
     * El invariante que el hito 2 dejó a medias, probado donde vive.
     *
     * <p>Que esté aquí y no solo en {@code DobleValidacionTest} es lo que demuestra que la regla es
     * del agregado: si hiciera falta levantar el servidor para verla, estaría en la puerta, y el
     * motor de integración —que escribe por otra— podría saltársela.
     */
    @Test
    void un_potasio_critico_no_queda_validado_con_una_sola_firma() {
        Resultado firmado = potasio("6.9").validar(CON_EL_POTASIO, FACULTATIVA, null);

        assertThat(firmado.firmas()).hasSize(1);
        assertThat(firmado.estaValidado())
                .as("firmado no es lo mismo que validado: al crítico le falta la revisión independiente")
                .isFalse();
        assertThat(firmado.estado()).isEqualTo(EstadoDeResultado.PENDIENTE_DE_SEGUNDA_FIRMA);
        assertThat(firmado.estado().esPublicable()).isFalse();
    }

    /**
     * La misma persona mirando dos veces no es una revisión independiente: quien leyó mal la cifra la
     * vuelve a leer mal treinta segundos después. Sin esta regla, el invariante sería un contador.
     */
    @Test
    void el_mismo_facultativo_no_pone_las_dos_firmas() {
        Resultado firmado = potasio("6.9").validar(CON_EL_POTASIO, FACULTATIVA, null);

        assertThatThrownBy(() -> firmado.validar(CON_EL_POTASIO, FACULTATIVA, null))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .hasMessageContaining("otro facultativo");
    }

    @Test
    void con_la_segunda_firma_de_otro_facultativo_el_critico_queda_validado() {
        Resultado validado =
                potasio("6.9").validar(CON_EL_POTASIO, FACULTATIVA, null).validar(CON_EL_POTASIO, OTRA, null);

        assertThat(validado.estado()).isEqualTo(EstadoDeResultado.VALIDADO);
        assertThat(validado.firmas())
                .as("las dos, y en el orden en que se pusieron: la primera es la revisión, la segunda la contra")
                .extracting(Validacion::facultativo)
                .containsExactly(FACULTATIVA, OTRA);
    }

    /** Control negativo: sin él, «exigir siempre dos» aprobaría los tres de arriba. */
    @Test
    void un_potasio_que_no_alcanza_el_umbral_se_valida_con_una_firma() {
        Resultado firmado = potasio("4.3").validar(CON_EL_POTASIO, FACULTATIVA, null);

        assertThat(firmado.estado()).isEqualTo(EstadoDeResultado.VALIDADO);
    }

    /**
     * Se pregunta una vez y la respuesta queda grabada.
     *
     * <p>Es lo que permite que una caída de la terminología entre las dos firmas no deje el resultado
     * atascado, y —lo que importa más— que un cambio del catálogo a mitad de camino no pueda rebajar
     * a una firma lo que empezó exigiendo dos.
     */
    @Test
    void lo_que_empezo_exigiendo_dos_firmas_no_se_cierra_con_una_porque_cambie_el_catalogo() {
        Resultado firmado = potasio("6.9").validar(CON_EL_POTASIO, FACULTATIVA, null);

        assertThat(firmado.firmasExigidas()).contains(2);
        assertThat(firmado.validar(SIN_UMBRALES, OTRA, null).estado())
                .as("la segunda firma la cierra igual: la obligación se estableció al firmar la primera")
                .isEqualTo(EstadoDeResultado.VALIDADO);
    }

    /**
     * Y si no se puede saber si es crítico, no se firma nada.
     *
     * <p>«No es crítico» no es una versión pobre de «no lo sé»: es la respuesta contraria, y es la
     * única de este sistema que se paga con una llamada de teléfono que no se hace.
     */
    @Test
    void sin_saber_si_es_critico_no_se_valida() {
        ValoresCriticos noContesta = codigoDePrueba -> {
            throw new NoSeSabeSiEsCritico("El servidor de terminología no ha contestado.");
        };
        Resultado medido = potasio("6.9");

        assertThatThrownBy(() -> medido.validar(noContesta, FACULTATIVA, null)).isInstanceOf(NoSeSabeSiEsCritico.class);
        assertThat(medido.estaValidado()).isFalse();
    }

    /** Aunque no se sepa si es crítico, una firma sin firmante sigue siendo el primer problema. */
    @Test
    void quien_firma_se_comprueba_antes_que_el_catalogo() {
        ValoresCriticos noContesta = codigoDePrueba -> {
            throw new NoSeSabeSiEsCritico("El servidor de terminología no ha contestado.");
        };

        assertThatThrownBy(() -> potasio("6.9").validar(noContesta, null, null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("quién");
    }

    private static Resultado potasio(String valor) {
        return Resultado.informarCuantitativo(
                unaMuestra(), null, "K", new BigDecimal(valor), "mmol/L", Medicion.sinConstancia(), null);
    }

    private static Resultado informar() {
        return Resultado.informarCuantitativo(
                unaMuestra(), null, "GLU", new BigDecimal("92"), "mg/dL", Medicion.sinConstancia(), null);
    }

    private static Especimen unaMuestra() {
        return Especimen.registrar(
                new NumeroDeAcceso("A" + UUID.randomUUID()),
                UUID.randomUUID(),
                "122555007",
                EstadoDeEspecimen.DISPONIBLE,
                null);
    }
}
