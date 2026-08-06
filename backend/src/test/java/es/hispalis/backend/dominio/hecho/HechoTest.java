package es.hispalis.backend.dominio.hecho;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.hispalis.backend.dominio.DatoInvalido;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El invariante 6 del proyecto —<strong>nada de PHI en el bus</strong>— probado donde se incumple.
 *
 * <p>{@code OutboxTransaccionalTest} comprueba que los hechos que hoy se escriben están limpios, y
 * hace falta; pero un test así solo cubre los hechos que <em>ya existen</em>. Este cubre los que
 * alguien escriba mañana: la fábrica rechaza cualquier valor que no sea una identidad de este
 * laboratorio, así que el descuido no llega ni a compilar un hecho, mucho menos a publicarse.
 */
class HechoTest {

    private static final UUID PACIENTE = UUID.randomUUID();

    @Test
    void un_hecho_lleva_su_paciente_dentro_de_la_carga() {
        Hecho hecho = Hecho.de(TipoDeHecho.PACIENTE_REGISTRADO, PACIENTE, Map.of());

        assertThat(hecho.claveDeParticion()).isEqualTo(PACIENTE);
        assertThat(hecho.carga())
                .as("quien lee el mensaje no ve la clave con la que se repartió")
                .containsEntry("pacienteId", PACIENTE.toString());
        assertThat(hecho.creadoEn()).isNotNull();
    }

    @Test
    void una_referencia_a_un_recurso_del_laboratorio_pasa() {
        UUID resultado = UUID.randomUUID();

        assertThatCode(() -> Hecho.de(
                        TipoDeHecho.RESULTADO_INFORMADO,
                        PACIENTE,
                        Map.of("observationRef", "Observation/" + resultado)))
                .doesNotThrowAnyException();
    }

    /**
     * Los cuatro que más caro salen. Ninguno tiene forma de identidad de este laboratorio, así que la
     * comprobación estructural los para sin necesidad de una lista de palabras prohibidas.
     */
    @ParameterizedTest
    @ValueSource(strings = {"12345678", "Muñoz Peñalver", "12345678Z", "AN0123456789", "Begoña", "92 mg/dL"})
    void ni_el_nhc_ni_el_nombre_ni_el_dni_ni_el_nuhsa_entran_en_un_hecho(String phi) {
        assertThatThrownBy(() -> Hecho.de(TipoDeHecho.PACIENTE_REGISTRADO, PACIENTE, Map.of("loQueSea", phi)))
                .isInstanceOf(DatoInvalido.class)
                .as("el mensaje tiene que decir qué campo es: si no, hay que buscarlo a mano")
                .hasMessageContaining("loQueSea");
    }

    /**
     * Un dato maestro con identificador propio tampoco pasa, y es deliberado: publicar a qué
     * laboratorio o a qué profesional se refiere un hecho es una decisión que merece pensarse.
     */
    @Test
    void un_identificador_que_no_es_uuid_tampoco_pasa() {
        assertThatThrownBy(() -> Hecho.de(
                        TipoDeHecho.RESULTADO_VALIDADO,
                        PACIENTE,
                        Map.of("practitionerRef", "Practitioner/analisis-clinicos")))
                .isInstanceOf(DatoInvalido.class);
    }

    @Test
    void un_hecho_sin_paciente_no_se_puede_repartir() {
        assertThatThrownBy(() -> Hecho.de(TipoDeHecho.PACIENTE_REGISTRADO, null, Map.of()))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("partición");
    }

    @Test
    void un_hecho_sin_tipo_no_dice_que_ha_pasado() {
        assertThatThrownBy(() -> Hecho.de(null, PACIENTE, Map.of())).isInstanceOf(DatoInvalido.class);
    }

    @Test
    void la_carga_no_se_puede_modificar_despues() {
        Hecho hecho = Hecho.de(TipoDeHecho.PACIENTE_REGISTRADO, PACIENTE, Map.of());

        assertThatThrownBy(() -> hecho.carga().put("apellidos", "Muñoz"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
