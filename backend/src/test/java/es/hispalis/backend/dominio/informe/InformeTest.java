package es.hispalis.backend.dominio.informe;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * El invariante completo del informe (§10 del diseño), probado <strong>sin Spring, sin HTTP y sin
 * base de datos</strong>.
 *
 * <p>«Solo se emite con todas las líneas resueltas» no es burocracia de estado: un informe que sale
 * con la mitad del volante hecho llega al peticionario con toda la apariencia de una respuesta
 * completa, y quien lo recibe <strong>deja de esperar lo que falta</strong>. Es el mismo daño que un
 * informe vacío, pero mucho más difícil de ver, porque este sí trae resultados dentro.
 *
 * <p>Como en C6, el test corre en el núcleo a propósito: si hiciera falta levantar la API para
 * probarlo, el invariante estaría en la puerta y dejaría de aplicarse en cuanto apareciera otra.
 */
class InformeTest {

    private static final UUID UN_PACIENTE = UUID.randomUUID();
    private static final UUID OTRO_PACIENTE = UUID.randomUUID();
    private static final String EMISOR = "Organization/laboratorio";
    private static final String VOLANTE = "P20260805-A1B2C3";

    @Test
    void un_informe_no_se_emite_con_una_linea_de_la_peticion_pendiente() {
        UUID lineaGlucosa = UUID.randomUUID();
        UUID lineaCreatinina = UUID.randomUUID();
        Resultado glucosa = resultado(UN_PACIENTE, lineaGlucosa, "GLU");

        assertThatThrownBy(() -> Informe.emitir(
                        List.of(glucosa),
                        List.of(
                                resuelta(lineaGlucosa, "GLU"),
                                // La creatinina del mismo volante sigue en el analizador.
                                pendiente(lineaCreatinina, "CREA")),
                        EMISOR,
                        null))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .as("quien lo recibe tiene que saber qué falta, no solo que falta algo")
                .hasMessageContaining("CREA")
                .hasMessageContaining(VOLANTE);
    }

    @Test
    void con_todas_las_lineas_resueltas_si_se_emite() {
        UUID lineaGlucosa = UUID.randomUUID();
        UUID lineaCreatinina = UUID.randomUUID();

        Informe informe = Informe.emitir(
                List.of(resultado(UN_PACIENTE, lineaGlucosa, "GLU"), resultado(UN_PACIENTE, lineaCreatinina, "CREA")),
                List.of(resuelta(lineaGlucosa, "GLU"), resuelta(lineaCreatinina, "CREA")),
                EMISOR,
                null);

        assertThat(informe.resultadoIds()).hasSize(2);
        assertThat(informe.pacienteId()).isEqualTo(UN_PACIENTE);
    }

    /**
     * Control negativo del alcance. Un caso de uso que construyera mal el alcance —dejando fuera
     * justo las líneas que le estorban— convertiría el invariante en decorado, y no daría ningún
     * error. La fábrica exige que toda línea citada por los resultados esté dentro.
     */
    @Test
    void un_alcance_al_que_le_falta_la_linea_de_un_resultado_esta_mal_construido() {
        Resultado glucosa = resultado(UN_PACIENTE, UUID.randomUUID(), "GLU");

        assertThatThrownBy(() -> Informe.emitir(List.of(glucosa), List.of(), EMISOR, null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("alcance");
    }

    /**
     * Un resultado sin línea de petición —una repetición de control, una determinación añadida en el
     * laboratorio— no tiene nada que resolver, así que no puede bloquear la emisión.
     */
    @Test
    void un_resultado_sin_peticion_previa_no_bloquea_nada() {
        Resultado suelto = resultado(UN_PACIENTE, null, "GLU");

        assertThatCode(() -> Informe.emitir(List.of(suelto), List.of(), EMISOR, null))
                .doesNotThrowAnyException();
    }

    @Test
    void un_informe_vacio_sigue_sin_emitirse() {
        assertThatThrownBy(() -> Informe.emitir(List.of(), List.of(), EMISOR, null))
                .isInstanceOf(ReglaDeNegocioIncumplida.class);
    }

    @Test
    void un_informe_sigue_sin_poder_mezclar_pacientes() {
        UUID linea = UUID.randomUUID();
        UUID otraLinea = UUID.randomUUID();

        assertThatThrownBy(() -> Informe.emitir(
                        List.of(resultado(UN_PACIENTE, linea, "GLU"), resultado(OTRO_PACIENTE, otraLinea, "GLU")),
                        List.of(resuelta(linea, "GLU"), resuelta(otraLinea, "GLU")),
                        EMISOR,
                        null))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("pacientes");
    }

    private static LineaDeLaPeticion resuelta(UUID id, String codigo) {
        return new LineaDeLaPeticion(id, VOLANTE, codigo, true);
    }

    private static LineaDeLaPeticion pendiente(UUID id, String codigo) {
        return new LineaDeLaPeticion(id, VOLANTE, codigo, false);
    }

    private static Resultado resultado(UUID paciente, UUID peticionId, String codigo) {
        Especimen muestra = Especimen.registrar(
                new NumeroDeAcceso("A" + UUID.randomUUID()), paciente, "122555007", EstadoDeEspecimen.DISPONIBLE, null);
        return Resultado.informarCuantitativo(
                muestra, peticionId, codigo, new BigDecimal("92"), "mg/dL", Medicion.sinConstancia());
    }
}
