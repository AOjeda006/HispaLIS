package es.hispalis.backend.dominio.peticion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.EstadoDeEspecimen;
import es.hispalis.backend.dominio.especimen.NumeroDeAcceso;
import es.hispalis.backend.dominio.resultado.Medicion;
import es.hispalis.backend.dominio.resultado.Resultado;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La anulación de una línea de petición, probada <strong>en el núcleo</strong>: sin Spring, sin HTTP
 * y sin base de datos.
 *
 * <p>El compañero {@code AnulacionDeLineaTest} comprueba lo mismo por la API. Los dos hacen falta y
 * prueban cosas distintas: aquel dice que la puerta HTTP se comporta, y este dice
 * <strong>dónde vive la regla</strong>. Si para probar el invariante hubiera que levantar la API,
 * estaría en la puerta — y dejaría de aplicarse en cuanto el hito 2 abra la segunda, que es el motor
 * de integración.
 */
class PeticionTest {

    private static final UUID PACIENTE = UUID.randomUUID();
    private static final String VOLANTE = "P20260806-9F3A";
    private static final String MOTIVO = "Muestra hemolizada y el paciente no vuelve a extracción.";

    @Test
    void una_linea_anulada_no_puede_producir_resultados() {
        Peticion anulada = linea("CREA").anular(false, MOTIVO, null);

        assertThatThrownBy(() -> resultadoDe(anulada))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .as("hay que decir qué línea y por qué se anuló, o el analizador no sabe qué ha pasado")
                .hasMessageContaining("CREA")
                .hasMessageContaining(VOLANTE)
                .hasMessageContaining(MOTIVO);
    }

    /**
     * Control positivo. Sin él, un invariante implementado como «rechaza siempre» pasaría el test de
     * arriba con matrícula de honor y dejaría al laboratorio sin poder informar una sola prueba.
     */
    @Test
    void una_linea_activa_produce_resultados_con_normalidad() {
        assertThatCode(() -> resultadoDe(linea("GLU"))).doesNotThrowAnyException();
    }

    @Test
    void anular_es_un_estado_nuevo_y_no_toca_la_linea_original() {
        Peticion original = linea("CREA");

        Peticion anulada = original.anular(false, MOTIVO, null);

        assertThat(original.estaAnulada())
                .as("el agregado es inmutable: anular devuelve otra línea, no muta esta")
                .isFalse();
        assertThat(anulada.estaAnulada()).isTrue();
        assertThat(anulada.id()).isEqualTo(original.id());
        assertThat(anulada.motivoDeAnulacion()).contains(MOTIVO);
        assertThat(anulada.anuladaEn()).isPresent();
    }

    @Test
    void anular_sin_motivo_deja_al_peticionario_a_ciegas() {
        assertThatThrownBy(() -> linea("CREA").anular(false, "   ", null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("por qué");
    }

    @Test
    void una_linea_ya_anulada_no_se_anula_dos_veces() {
        Peticion anulada = linea("CREA").anular(false, MOTIVO, null);

        assertThatThrownBy(() -> anulada.anular(false, "otro motivo", null))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .hasMessageContaining("ya estaba anulada");
    }

    /**
     * Si se pudiera, el resultado quedaría publicado colgando de una línea que dice que no se hizo, y
     * el informe que lo entregó quedaría contradicho por su propia petición.
     */
    @Test
    void una_linea_con_resultado_no_se_anula() {
        assertThatThrownBy(() -> linea("GLU").anular(true, MOTIVO, null))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .hasMessageContaining("ya tiene resultado");
    }

    private static Peticion linea(String codigo) {
        return Peticion.registrar(VOLANTE, PACIENTE, codigo, "Practitioner/peticionario", null);
    }

    private static Resultado resultadoDe(Peticion linea) {
        Especimen muestra = Especimen.registrar(
                new NumeroDeAcceso("A" + UUID.randomUUID()),
                linea.pacienteId(),
                "122555007",
                EstadoDeEspecimen.DISPONIBLE,
                null);
        return Resultado.informarCuantitativo(
                muestra,
                linea,
                linea.codigoDePrueba(),
                new BigDecimal("1.02"),
                "mg/dL",
                Medicion.sinConstancia(),
                null);
    }
}
