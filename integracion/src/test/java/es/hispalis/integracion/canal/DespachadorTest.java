package es.hispalis.integracion.canal;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.hl7v2.model.Message;
import es.hispalis.integracion.almacen.AlmacenDeMensajes;
import es.hispalis.integracion.almacen.MensajeArchivado;
import es.hispalis.integracion.almacen.MensajeEntrante;
import es.hispalis.integracion.hl7.CabeceraMsh;
import es.hispalis.integracion.hl7.CharsetDeclarado;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La red del despachador: qué pasa cuando un canal lanza algo que nadie esperaba.
 *
 * <p>Es una red de dos hilos y hasta el 2026-08-15 solo tenía uno. Que el fallo no tumbe la conexión
 * estaba probado de paso por los tests de canal; que <strong>lo que sale por el cable no lleve dentro
 * el mensaje de la excepción</strong> no lo comprobaba nadie, y esa mitad es la que importa: el
 * mensaje de una excepción inesperada puede ser cualquier cosa —el fuzzing encontró una que traía la
 * sentencia {@code INSERT} del archivo entera— y quien lee el acuse es un sistema ajeno.
 *
 * <p>Se prueba con un canal de mentira y no de extremo a extremo a propósito. Un fallo
 * <em>inesperado</em>, por definición, no se puede provocar desde fuera: si se pudiera, ya tendría su
 * {@code catch} y no llegaría aquí. Lo único honesto es inyectar uno.
 */
class DespachadorTest {

    /** Lo que jamás puede salir por el acuse, aunque venga dentro del mensaje de la excepción. */
    private static final String SECRETO_DEL_LABORATORIO =
            "INSERT INTO integracion.mensaje (id, aplicacion_emisora) VALUES (?, ?)";

    private final ArchivoDeMentira archivo = new ArchivoDeMentira();

    @Test
    void un_fallo_inesperado_del_canal_no_sale_por_el_cable_pero_si_al_archivo() {
        Despachador despachador = new Despachador(List.of(new CanalQueRevienta()), archivo);
        MensajeEntrante mensaje = unAdt();

        Desenlace desenlace = despachador.aplicar(mensaje, null);

        assertThat(desenlace.detalle())
                .as("esto es lo que viaja en el ERR del acuse: lo lee el HIS del hospital")
                .doesNotContain(SECRETO_DEL_LABORATORIO)
                .doesNotContain("ADT")
                .isEqualTo(Desenlace.FALLO_INTERNO);
        assertThat(desenlace.detalleTecnico())
                .as("y esto es lo que se queda dentro, que es donde lo busca quien diagnostica")
                .contains(SECRETO_DEL_LABORATORIO);
        assertThat(desenlace.seAplico()).isFalse();
    }

    @Test
    void el_mensaje_que_revienta_queda_en_la_bandeja_con_el_motivo_de_verdad() {
        Despachador despachador = new Despachador(List.of(new CanalQueRevienta()), archivo);
        MensajeEntrante mensaje = unAdt();

        despachador.aplicar(mensaje, null);

        assertThat(archivo.rechazados)
                .as("sin fila en la bandeja no hay nada que reprocesar y el mensaje se pierde")
                .hasSize(1);
        assertThat(archivo.rechazados.getFirst().motivo()).contains(SECRETO_DEL_LABORATORIO);
    }

    /** Un tipo de mensaje que este laboratorio no atiende se rechaza, no revienta. */
    @Test
    void sin_canal_para_el_tipo_se_rechaza_diciendo_cual_era() {
        Despachador despachador = new Despachador(List.of(), archivo);

        Desenlace desenlace = despachador.aplicar(unAdt(), null);

        assertThat(desenlace.seAplico()).isFalse();
        assertThat(desenlace.detalle()).contains("ADT^A01");
        assertThat(archivo.rechazados).hasSize(1);
    }

    // ── Dobles ───────────────────────────────────────────────────────────────────────────────────

    /** Un canal que falla como fallan los de verdad: con lo que no se esperaba. */
    private static final class CanalQueRevienta implements Canal {

        @Override
        public String nombre() {
            return "adt-paciente";
        }

        @Override
        public boolean acepta(CabeceraMsh cabecera) {
            return true;
        }

        @Override
        public Indices indices(Message recibido) {
            return Indices.NINGUNO;
        }

        @Override
        public Desenlace procesar(MensajeEntrante mensaje, Message recibido) {
            throw new IllegalStateException(SECRETO_DEL_LABORATORIO);
        }
    }

    private record Rechazo(UUID id, String motivo) {}

    private static final class ArchivoDeMentira implements AlmacenDeMensajes {

        private final List<Rechazo> rechazados = new ArrayList<>();
        private final List<Rechazo> procesados = new ArrayList<>();

        @Override
        public Admision registrarSiEsNuevo(MensajeEntrante mensaje) {
            return Admision.NUEVO;
        }

        @Override
        public void marcarProcesado(UUID id, String referenciaProducida) {
            procesados.add(new Rechazo(id, referenciaProducida));
        }

        @Override
        public void marcarRechazado(UUID id, String motivo) {
            rechazados.add(new Rechazo(id, motivo));
        }

        @Override
        public List<MensajeArchivado> bandejaDeErrores(int limite) {
            return List.of();
        }

        @Override
        public Optional<MensajeArchivado> buscar(UUID id) {
            return Optional.empty();
        }

        @Override
        public void anotarIntento(UUID id) {
            // No hace falta para lo que se comprueba aquí.
        }
    }

    private static MensajeEntrante unAdt() {
        CabeceraMsh cabecera = new CabeceraMsh(
                "HIS_VIRGEN",
                "H_VIRGEN_MACARENA",
                "ADT",
                "A01",
                "ADT_A01",
                "CTRL-1",
                "2.5.1",
                CharsetDeclarado.de("8859/1"));
        return new MensajeEntrante(UUID.randomUUID(), cabecera, "70000001", null, "MSH|^~\\&|…", Instant.now());
    }
}
