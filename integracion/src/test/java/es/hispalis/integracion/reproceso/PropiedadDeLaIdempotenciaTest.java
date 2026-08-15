package es.hispalis.integracion.reproceso;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.arnes.NombresEspanoles;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * <strong>Propiedad:</strong> aplicar {@code n} veces el mismo mensaje deja el mismo estado que
 * aplicarlo una. Para cualquier {@code n} y para cualquiera de los tres canales.
 *
 * <p>{@code DlqYReprocesoTest} ya lo comprueba con un ejemplo —un {@code OML} y tres reprocesos—, y
 * ese test prueba <strong>D22</strong>: que la atomicidad la puede poner el reproceso. Lo que este
 * añade es el cuantificador. La idempotencia es una propiedad universal o no es una propiedad: «tres
 * veces con un OML» deja sin comprobar que el cuarto reproceso no duplique, que el canal de
 * demografía se comporte igual, y que un mensaje que <em>nunca</em> llegó a fallar aguante también el
 * trato.
 *
 * <p>Lo que se afirma para cada caso son tres cosas a la vez, porque las tres tienen que darse para
 * que la palabra «idempotente» signifique algo:
 *
 * <ol>
 *   <li><strong>Una fila</strong> en el archivo, no {@code n}. El reproceso reescribe la fila del
 *       mensaje, no crea una copia por intento.
 *   <li><strong>El mismo estado</strong> en el laboratorio: la foto de todo lo guardado, recurso a
 *       recurso y campo a campo, es byte a byte la de después de la primera entrega.
 *   <li><strong>El contador de intentos</strong> vale {@code 1 + n}. Si no, el reproceso no está
 *       pasando por donde cree.
 * </ol>
 *
 * <h2>Por qué el estado y no la cuenta de escrituras</h2>
 *
 * <p>La primera versión de este test afirmaba lo segundo contando escrituras —«las reaplicaciones
 * buscaron y no escribieron»— y salió en rojo en los cinco casos del canal de demografía, con una
 * escritura de más por reproceso. No era un fallo: {@code CanalAdtPaciente} busca el NHC y, si lo
 * encuentra, <strong>corrige</strong> la filiación con un {@code PUT} sobre el mismo recurso. Los dos
 * canales clínicos, al encontrar lo suyo, se lo saltan; el de demografía no puede, porque un
 * {@code A08} <em>es</em> una corrección y saltársela sería perderla.
 *
 * <p>El rojo era del test, no del motor: «idempotente» dice que el estado final no cambia, no que no
 * se toque el disco. Afirmar la cuenta de escrituras habría convertido un detalle de implementación de
 * un canal en un contrato, y el día que otro canal decidiera reescribir por lo mismo, el rojo habría
 * apuntado al sitio equivocado.
 */
class PropiedadDeLaIdempotenciaTest extends TestDelMotor {

    private static final long SEMILLA = 20_260_815L;
    private static final String SUERO = "119364003";
    private static final AtomicInteger SIGUIENTE = new AtomicInteger(77_000_000);

    @Autowired
    private Reproceso reproceso;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void decirConQueSemillaSeCorre() {
        System.out.printf("Propiedad de la idempotencia del reproceso: semilla %d.%n", SEMILLA);
    }

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    /** Los tres canales por {@code n} de uno a cinco. Los nombres, generados. */
    static Stream<Arguments> canalesYRepeticiones() {
        NombresEspanoles nombres = new NombresEspanoles(SEMILLA);
        List<String> canales = List.of("ADT", "OML", "ORU");
        return canales.stream().flatMap(canal -> IntStream.rangeClosed(1, 5)
                .mapToObj(n -> Arguments.of(canal, n, nombres.apellidosEnMayusculas())));
    }

    @ParameterizedTest(name = "{0} reprocesado {1} vez/veces ({2})")
    @MethodSource("canalesYRepeticiones")
    void reprocesar_n_veces_deja_el_mismo_estado_que_aplicarlo_una(String canal, int veces, String apellidos) {
        String nhc = registrarPaciente(apellidos);
        String control = "%s-IDEM-%s-%d".formatted(canal, nhc, veces);
        elHis().enviar(mensaje(canal, control, nhc, apellidos));

        String estadoTrasLaEntrega = LABORATORIO.inventario();
        UUID id = idEnElArchivo(control);
        IntStream.range(0, veces).forEach(intento -> reproceso.reaplicar(id));

        assertThat(filasCon(control))
                .as("el reproceso reescribe LA MISMA fila; una copia por intento haría inútil el archivo")
                .isEqualTo(1);
        assertThat(LABORATORIO.inventario())
                .as("los %d reprocesos dejaron el laboratorio exactamente como estaba", veces)
                .isEqualTo(estadoTrasLaEntrega);
        assertThat((Integer) archivado(control).get("intentos"))
                .as("la entrega original más los %d reprocesos", veces)
                .isEqualTo(1 + veces);
    }

    private static String mensaje(String canal, String control, String nhc, String apellidos) {
        return switch (canal) {
            case "ADT" -> MensajesDePrueba.adt("A08", control, nhc, apellidos, "Rocío^Ana", "8859/1");
            case "OML" -> MensajesDePrueba.oml(control, nhc, "P-IDEM-" + nhc, "ACC" + nhc, SUERO, "GLU", "CREA");
            default -> MensajesDePrueba.oru(control, nhc, "ACC" + nhc, "99HISPALIS", "GLU|NM|92|mg/dL");
        };
    }

    /**
     * El paciente ya está en el laboratorio antes de empezar.
     *
     * <p>Hace falta para los tres canales, y no solo para los dos clínicos: el mensaje de demografía
     * que se reprocesa es un {@code A08} —una corrección—, precisamente porque un {@code A01} repetido
     * comprobaría la idempotencia del alta y no la del reproceso.
     */
    private static String registrarPaciente(String apellidos) {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());
        Patient paciente = new Patient();
        paciente.addIdentifier(
                new Identifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc));
        paciente.addName().setFamily(apellidos).addGiven("Rocío").addGiven("Ana");
        LABORATORIO.sembrar(paciente);
        return nhc;
    }

    private int filasCon(String control) {
        Integer cuantas = jdbc.queryForObject(
                "SELECT count(*) FROM integracion.mensaje WHERE control_id = :control",
                new MapSqlParameterSource("control", control),
                Integer.class);
        return cuantas == null ? 0 : cuantas;
    }

    private Map<String, Object> archivado(String control) {
        return jdbc.queryForMap(
                "SELECT estado, intentos FROM integracion.mensaje WHERE control_id = :control",
                new MapSqlParameterSource("control", control));
    }

    private UUID idEnElArchivo(String control) {
        return jdbc.queryForObject(
                "SELECT id FROM integracion.mensaje WHERE control_id = :control",
                new MapSqlParameterSource("control", control),
                UUID.class);
    }
}
