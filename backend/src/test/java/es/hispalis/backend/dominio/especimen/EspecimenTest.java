package es.hispalis.backend.dominio.especimen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.resultado.Resultado;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El invariante de C6, probado <strong>sin Spring, sin HTTP y sin base de datos</strong>.
 *
 * <p>Eso no es una comodidad del test: es la comprobación de que el invariante vive de verdad en el
 * núcleo y no en el {@code ResourceProvider}. Si para probarlo hiciera falta levantar la API, es que
 * estaría en la puerta, y una regla que solo se aplica en una puerta deja de aplicarse en cuanto
 * aparece otra — el motor de integración, un script de mantenimiento, una carga masiva.
 */
class EspecimenTest {

    private static final UUID UN_PACIENTE = UUID.randomUUID();
    private static final String SANGRE_VENOSA = "122555007";

    /** Un resultado puede existir sin petición previa: una repetición de control, por ejemplo. */
    private static final UUID SIN_PETICION = null;

    @Test
    void una_muestra_rechazada_no_puede_producir_resultados() {
        Especimen rechazada = muestra(EstadoDeEspecimen.RECHAZADA, "Hemolizada");

        assertThatThrownBy(rechazada::exigirQuePuedeProducirResultados)
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .hasMessageContaining("rechazada")
                .as("el motivo va en el mensaje: quien lo reciba no debería tener que preguntarlo")
                .hasMessageContaining("Hemolizada");
    }

    @Test
    void una_muestra_disponible_si_puede() {
        assertThatCode(muestra(EstadoDeEspecimen.DISPONIBLE, null)::exigirQuePuedeProducirResultados)
                .doesNotThrowAnyException();
    }

    @Test
    void una_muestra_consumida_tampoco_puede() {
        // No es un rechazo, pero el resultado es el mismo: ya no hay de qué medir.
        assertThatThrownBy(muestra(EstadoDeEspecimen.NO_DISPONIBLE, null)::exigirQuePuedeProducirResultados)
                .isInstanceOf(ReglaDeNegocioIncumplida.class);
    }

    @Test
    void no_hay_forma_de_construir_un_resultado_a_partir_de_una_muestra_rechazada() {
        // La comprobación está en la fábrica del agregado, no en quien la llama. Es la diferencia
        // entre un invariante y una recomendación: no existe un camino que se la salte.
        Especimen rechazada = muestra(EstadoDeEspecimen.RECHAZADA, "Coagulada");

        assertThatThrownBy(() ->
                        Resultado.informarCuantitativo(rechazada, SIN_PETICION, "GLU", new BigDecimal("92"), "mg/dL"))
                .isInstanceOf(ReglaDeNegocioIncumplida.class);
    }

    @Test
    void rechazar_sin_decir_por_que_no_esta_permitido() {
        // Rechazar sin motivo obliga al peticionario a llamar por teléfono. Es la invariante
        // `hlis-esp-1` de la guía, aquí en el sitio que manda.
        assertThatThrownBy(() -> muestra(EstadoDeEspecimen.RECHAZADA, null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("motivo");
    }

    @Test
    void una_muestra_sin_numero_de_acceso_no_es_trazable() {
        assertThatThrownBy(() -> new NumeroDeAcceso("  "))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("número de acceso");
    }

    @Test
    void el_resultado_hereda_paciente_y_muestra_del_especimen() {
        Especimen disponible = muestra(EstadoDeEspecimen.DISPONIBLE, null);

        Resultado resultado =
                Resultado.informarCuantitativo(disponible, SIN_PETICION, "GLU", new BigDecimal("92"), "mg/dL");

        assertThat(resultado.especimenId()).isEqualTo(disponible.id());
        assertThat(resultado.pacienteId()).isEqualTo(UN_PACIENTE);
        assertThat(resultado.unidadUcum()).contains("mg/dL");
    }

    @Test
    void una_cifra_sin_unidad_no_significa_nada() {
        Especimen disponible = muestra(EstadoDeEspecimen.DISPONIBLE, null);

        assertThatThrownBy(() ->
                        Resultado.informarCuantitativo(disponible, SIN_PETICION, "GLU", new BigDecimal("92"), null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("UCUM");
    }

    private static Especimen muestra(EstadoDeEspecimen estado, String motivo) {
        return Especimen.registrar(new NumeroDeAcceso("A12345"), UN_PACIENTE, SANGRE_VENOSA, estado, motivo);
    }
}
