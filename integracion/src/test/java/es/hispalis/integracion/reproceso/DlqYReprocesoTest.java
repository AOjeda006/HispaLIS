package es.hispalis.integracion.reproceso;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * La DLQ y el reproceso, que son <strong>la</strong> prueba de la decisión D22.
 *
 * <p>D22 dice que el motor escribe recurso a recurso y que la atomicidad la pone el reproceso. Eso
 * solo es cierto si se cumplen dos cosas, y las dos se comprueban aquí:
 *
 * <ol>
 *   <li>Un fallo <strong>a mitad</strong> deja lo escrito escrito y el mensaje en la bandeja, con lo
 *       que pasó anotado. No se pierde nada y nada queda a medias en silencio.
 *   <li>Reprocesar <strong>completa</strong> lo que faltaba y <strong>no duplica</strong> lo que ya
 *       estaba — ni la primera vez ni la tercera.
 * </ol>
 *
 * <p>Si el segundo punto no se cumpliera, D22 sería una suposición y la salida correcta habría sido
 * la (a): abrir la puerta transaccional. Por eso este test no prueba una utilidad, prueba una
 * decisión.
 */
class DlqYReprocesoTest extends TestDelMotor {

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(73_000_000);
    private static final String SUERO = "119364003";

    @Autowired
    private Reproceso reproceso;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    /**
     * El caso que motivó D22: el volante se escribe y la muestra falla.
     *
     * <p>Lo que queda es un {@code ServiceRequest} sin {@code Specimen}: la <em>ventana de
     * huérfano</em>. Es un estado transitorio legítimo y documentado, no un agujero — tiene dueño, y
     * el dueño es el reproceso.
     */
    @Test
    void un_oml_que_falla_al_escribir_la_muestra_deja_el_volante_y_va_a_la_dlq() {
        String nhc = registrarPaciente();
        String control = "OMLFALLO" + nhc;
        LABORATORIO.fallarLaProximaEscrituraDe("Specimen");

        String acuse = elHis().enviar(MensajesDePrueba.oml(control, nhc, "P-D22", "ACC" + nhc, SUERO, "GLU", "CREA"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AE");
        assertThat(LABORATORIO.guardados(ServiceRequest.class))
                .as("lo escrito antes del fallo se queda escrito: la ventana de huérfano")
                .hasSize(2);
        assertThat(LABORATORIO.guardados(Specimen.class)).isEmpty();

        assertThat(enLaBandeja(control))
                .as("y el mensaje queda en la bandeja de errores, con su motivo")
                .isPresent()
                .get()
                .satisfies(
                        entrada -> assertThat(entrada.get("detalle").toString()).contains("Specimen"));
    }

    /**
     * Y reprocesar lo completa. Tres veces, y el estado final es el mismo.
     *
     * <p>Tres y no dos a propósito: con dos, un reproceso que duplicara solo en la primera repetición
     * podría pasar por bueno. Lo que se está comprobando es que la operación es idempotente, no que
     * «funciona la segunda vez».
     */
    @Test
    void reprocesar_tres_veces_completa_lo_que_faltaba_y_no_duplica_nada() {
        String nhc = registrarPaciente();
        String control = "OMLREPRO" + nhc;
        LABORATORIO.fallarLaProximaEscrituraDe("Specimen");
        elHis().enviar(MensajesDePrueba.oml(control, nhc, "P-REPRO", "ACC" + nhc, SUERO, "GLU", "CREA"));

        UUID id = idEnElArchivo(control);
        Reproceso.Resultado primero = reproceso.reaplicar(id);
        Reproceso.Resultado segundo = reproceso.reaplicar(id);
        Reproceso.Resultado tercero = reproceso.reaplicar(id);

        assertThat(List.of(primero, segundo, tercero))
                .allSatisfy(resultado -> assertThat(resultado.aplicado()).isTrue());

        assertThat(LABORATORIO.guardados(ServiceRequest.class))
                .as("dos líneas, las mismas dos, después de tres reprocesos")
                .hasSize(2);
        assertThat(LABORATORIO.guardados(Specimen.class))
                .as("y una sola muestra, la que faltaba")
                .hasSize(1);
        assertThat(LABORATORIO.escriturasDe(ServiceRequest.class))
                .as("las líneas se escribieron UNA vez: los reprocesos las encontraron y no las tocaron")
                .isEqualTo(2);
        assertThat(LABORATORIO.escriturasDe(Specimen.class))
                .as("y la muestra también, en el primer reproceso")
                .isEqualTo(1);

        Map<String, Object> fila = enLaBandeja(control).orElse(archivado(control));
        assertThat(fila.get("estado")).isEqualTo("PROCESADO");
        assertThat((Integer) fila.get("intentos"))
                .as("la entrega original más los tres reprocesos")
                .isEqualTo(4);
    }

    /** Un mensaje aplicado se puede reprocesar igualmente, y sigue sin duplicar. */
    @Test
    void reprocesar_un_mensaje_que_ya_estaba_aplicado_no_escribe_nada_nuevo() {
        String nhc = registrarPaciente();
        String control = "OMLOK" + nhc;
        elHis().enviar(MensajesDePrueba.oml(control, nhc, "P-OK", "ACC" + nhc, SUERO, "GLU"));
        int escriturasTrasLaEntrega = LABORATORIO.escrituras();

        Reproceso.Resultado resultado = reproceso.reaplicar(idEnElArchivo(control));

        assertThat(resultado.aplicado()).isTrue();
        assertThat(LABORATORIO.escrituras())
                .as("el reproceso buscó, encontró y no escribió")
                .isEqualTo(escriturasTrasLaEntrega);
    }

    /** El original íntegro se guarda también cuando el mapeo falla: sin él no hay nada que reprocesar. */
    @Test
    void un_mensaje_que_falla_el_mapeo_conserva_su_original_intacto() {
        String nhc = registrarPaciente();
        String control = "OMLMAL" + nhc;
        String mensaje = MensajesDePrueba.oml(control, nhc, "P-MAL", "ACC" + nhc, SUERO, "NOEXISTE");

        elHis().enviar(mensaje);

        Map<String, Object> fila = archivado(control);
        assertThat(fila.get("estado")).isEqualTo("RECHAZADO");
        assertThat(fila.get("crudo"))
                .as("lo archivado es lo que llegó por el hilo, no una reserialización")
                .isEqualTo(mensaje);
    }

    /** Reprocesar algo que no está en el archivo no revienta: avisa. */
    @Test
    void reprocesar_un_identificador_que_no_existe_avisa_en_vez_de_reventar() {
        UUID inventado = UUID.randomUUID();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> reproceso.reaplicar(inventado)))
                .isInstanceOf(Reproceso.MensajeDesconocido.class)
                .hasMessageContaining(inventado.toString());
    }

    private java.util.Optional<Map<String, Object>> enLaBandeja(String control) {
        return jdbc
                .queryForList(
                        "SELECT id, estado, detalle, intentos FROM integracion.mensaje "
                                + "WHERE control_id = :control AND estado = 'RECHAZADO'",
                        new MapSqlParameterSource("control", control))
                .stream()
                .findFirst();
    }

    private Map<String, Object> archivado(String control) {
        return jdbc.queryForMap(
                "SELECT id, estado, detalle, intentos, crudo FROM integracion.mensaje WHERE control_id = :control",
                new MapSqlParameterSource("control", control));
    }

    private UUID idEnElArchivo(String control) {
        return jdbc.queryForObject(
                "SELECT id FROM integracion.mensaje WHERE control_id = :control",
                new MapSqlParameterSource("control", control),
                UUID.class);
    }

    private static String registrarPaciente() {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());
        Patient paciente = new Patient();
        paciente.addIdentifier(
                new Identifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc));
        paciente.addName().setFamily(MensajesDePrueba.FERNANDEZ).addGiven("Álvaro");
        LABORATORIO.sembrar(paciente);
        return nhc;
    }

    private static String codigoDeAcuse(String acuse) {
        return List.of(acuse.split("\r")).stream()
                .filter(segmento -> segmento.startsWith("MSA|"))
                .map(segmento -> segmento.split("\\|")[1])
                .findFirst()
                .orElseThrow(() -> new AssertionError("El acuse no trae MSA: " + acuse));
    }
}
