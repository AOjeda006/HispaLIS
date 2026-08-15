package es.hispalis.integracion.mllp;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.arnes.NombresEspanoles;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.StringType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <strong>Propiedad:</strong> escribir un nombre en el juego que declara el mensaje y volverlo a leer
 * es la identidad. Para cualquier nombre español, no solo para {@code MUÑOZ}.
 *
 * <p>Es el {@code decode(encode(x)) == x} de este sistema, y se afirma <strong>sobre el hilo</strong>
 * y no sobre una función: el emisor codifica los bytes según el {@code MSH-18} de cada mensaje, el
 * servidor los decodifica según ese mismo campo, el canal traduce y el laboratorio guarda un
 * {@code Patient}. Lo que se compara al final es la cadena que entró contra la que salió, con todo el
 * recorrido en medio.
 *
 * <p>Hasta ahora esto estaba probado con tres apellidos escritos a mano. Tres apellidos son tres
 * ejemplos: prueban que esos tres funcionan. Aquí las entradas se generan —eñes, tildes, cedillas,
 * apellidos dobles y partículas— y la afirmación pasa a ser sobre el conjunto.
 *
 * <p><strong>Por qué no está {@code ASCII} entre los juegos.</strong> Está en la lista corta que este
 * laboratorio acepta, pero no puede representar una {@code Ñ}: exigir que {@code MUÑOZ} sobreviva a un
 * {@code MSH-18} que dice {@code ASCII} no sería una propiedad incumplida del motor, sería aritmética.
 * Lo que sí hay que exigir de ese caso —que se rechace en vez de guardar {@code MU?OZ}— es un ejemplo
 * y vive en {@code CanalAdtPacienteTest}.
 */
class PropiedadDelCharsetEnElHiloTest extends TestDelMotor {

    /** Fija y se imprime: sin eso, un contraejemplo no se puede repetir. */
    private static final long SEMILLA = 20_260_815L;

    private static final int CASOS = 30;

    /** Los tres juegos aceptados que sí saben escribir un nombre español. */
    private static final List<String> JUEGOS = List.of("8859/1", "8859/15", "UNICODE UTF-8");

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(75_000_000);

    @BeforeAll
    static void decirConQueSemillaSeCorre() {
        System.out.printf("Propiedad del charset: semilla %d, %d nombres generados.%n", SEMILLA, CASOS);
    }

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    static Stream<Arguments> nombresGenerados() {
        NombresEspanoles nombres = new NombresEspanoles(SEMILLA);
        return IntStream.range(0, CASOS)
                .mapToObj(i -> Arguments.of(
                        nombres.apellidosEnMayusculas(), nombres.nombreDePila(), JUEGOS.get(i % JUEGOS.size())));
    }

    @ParameterizedTest(name = "{0}^{1} en {2}")
    @MethodSource("nombresGenerados")
    void cualquier_nombre_espanol_sale_del_hilo_tal_y_como_entro(String apellidos, String pila, String juego) {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());

        String acuse = elHis().enviar(MensajesDePrueba.adt("A01", "PROP" + nhc, nhc, apellidos, pila, juego));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");
        Patient publicado = LABORATORIO.altas().get(0);
        assertThat(publicado.getNameFirstRep().getFamily())
                .as("el nombre familiar viaja entero y con sus caracteres: ni se parte ni se transcribe")
                .isEqualTo(apellidos);
        assertThat(publicado.getNameFirstRep().getGiven())
                .extracting(StringType::getValue)
                .containsExactly(pila.split("\\^"));
    }

    /** {@code MSA-1} del acuse. */
    private static String codigoDeAcuse(String acuse) {
        return List.of(acuse.split("\r")).stream()
                .filter(segmento -> segmento.startsWith("MSA|"))
                .map(segmento -> segmento.split("\\|")[1])
                .findFirst()
                .orElseThrow(() -> new AssertionError("El acuse no trae MSA: " + acuse));
    }
}
