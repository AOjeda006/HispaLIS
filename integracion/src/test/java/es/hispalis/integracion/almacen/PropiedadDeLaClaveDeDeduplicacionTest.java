package es.hispalis.integracion.almacen;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * <strong>Propiedad:</strong> dos mensajes son el mismo si y solo si coinciden en
 * {@code MSH-3}, {@code MSH-4} <em>y</em> {@code MSH-10}. En cualquier otro caso son dos.
 *
 * <p>Es un <em>si y solo si</em>, y las dos direcciones fallan de forma distinta:
 *
 * <ul>
 *   <li>Si la clave fuera <strong>más estrecha</strong> —solo {@code MSH-10}, que es lo que dice el
 *       tópico y lo que este motor deliberadamente no hace— dos analizadores que reinician su
 *       contador coincidirían a la primera y el segundo resultado se descartaría <strong>en
 *       silencio</strong>. Un resultado de laboratorio que no llega y del que nadie se entera.
 *   <li>Si fuera <strong>más ancha</strong> —incluyendo, por ejemplo, la marca de tiempo— la
 *       deduplicación no deduplicaría nada y cada reintento del emisor escribiría otra vez.
 * </ul>
 *
 * <p>Por eso se prueba con las <strong>ocho</strong> combinaciones de qué campos cambian, y no con un
 * ejemplo de cada dirección: son ocho y hay que ver las ocho. Los valores se generan para que la
 * afirmación no dependa de los tres literales de siempre.
 *
 * <p>Lo que se cuenta son <strong>filas del archivo</strong> y no escrituras en el laboratorio. La
 * clave de deduplicación es una restricción única de la base del motor y ahí es donde se cumple o no;
 * lo que el canal haga después con el segundo mensaje es otra pregunta, con su propio test.
 */
class PropiedadDeLaClaveDeDeduplicacionTest extends TestDelMotor {

    private static final long SEMILLA = 20_260_815L;

    /** Las ocho combinaciones, tres veces cada una con valores distintos. */
    private static final int CASOS = 24;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void decirConQueSemillaSeCorre() {
        System.out.printf("Propiedad de la clave de deduplicación: semilla %d, %d pares.%n", SEMILLA, CASOS);
    }

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    /**
     * Cada caso: una clave base, y qué campos de los tres cambia el segundo mensaje.
     *
     * @param mascara bits {@code 001} = cambia {@code MSH-10}, {@code 010} = {@code MSH-4},
     *     {@code 100} = {@code MSH-3}. El cero es «no cambia nada», que es el único caso que
     *     deduplica
     */
    static Stream<Arguments> pares() {
        Random azar = new Random(SEMILLA);
        return IntStream.range(0, CASOS).mapToObj(i -> {
            String sufijo = Integer.toHexString(azar.nextInt(0x1000000)).toUpperCase();
            return Arguments.of(i % 8, "HIS" + sufijo, "FAC" + sufijo, "CTRL" + sufijo, "76%06d".formatted(i));
        });
    }

    @ParameterizedTest(name = "cambian {0}: {1}/{2}/{3}")
    @MethodSource("pares")
    void dos_mensajes_son_uno_solo_si_y_solo_si_coinciden_los_tres_campos(
            int mascara, String emisor, String instalacion, String control, String nhc) {

        String otroEmisor = (mascara & 0b100) != 0 ? emisor + "_B" : emisor;
        String otraInstalacion = (mascara & 0b010) != 0 ? instalacion + "_B" : instalacion;
        String otroControl = (mascara & 0b001) != 0 ? control + "_B" : control;

        elHis().enviar(adt(emisor, instalacion, control, nhc));
        elHis().enviar(adt(otroEmisor, otraInstalacion, otroControl, nhc));

        int esperadas = mascara == 0 ? 1 : 2;
        assertThat(filasDe(emisor, instalacion, control, otroEmisor, otraInstalacion, otroControl))
                .as(
                        "con la máscara %s, el archivo tendría que guardar %d fila(s)",
                        Integer.toBinaryString(0b1000 | mascara).substring(1), esperadas)
                .isEqualTo(esperadas);
    }

    private static String adt(String emisor, String instalacion, String control, String nhc) {
        return MensajesDePrueba.adtDe(
                emisor, instalacion, "A01", control, nhc, MensajesDePrueba.PENA, "Rocío^Ana", "8859/1", "ADT_A01");
    }

    private int filasDe(
            String emisor,
            String instalacion,
            String control,
            String otroEmisor,
            String otraInstalacion,
            String otroControl) {
        Integer cuantas = jdbc.queryForObject(
                """
                SELECT count(*) FROM integracion.mensaje
                 WHERE (aplicacion_emisora = :emisor AND instalacion_emisora = :instalacion AND control_id = :control)
                    OR (aplicacion_emisora = :otroEmisor AND instalacion_emisora = :otraInstalacion
                        AND control_id = :otroControl)
                """,
                new MapSqlParameterSource()
                        .addValue("emisor", emisor)
                        .addValue("instalacion", instalacion)
                        .addValue("control", control)
                        .addValue("otroEmisor", otroEmisor)
                        .addValue("otraInstalacion", otraInstalacion)
                        .addValue("otroControl", otroControl),
                Integer.class);
        return cuantas == null ? 0 : cuantas;
    }
}
