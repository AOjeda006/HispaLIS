package es.hispalis.integracion.saliente;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.hl7v2.AcknowledgmentCode;
import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.OutboxDelBackend;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * El {@code ORU^R01} saliente: cuando el laboratorio emite un informe, el HIS lo recibe.
 *
 * <p>Lo que se comprueba aquí no es solo que el mensaje llegue: es <strong>de dónde sale el
 * disparo</strong>. Se apunta un hecho en el {@code outbox} —igual que lo apuntaría el laboratorio
 * dentro de su transacción— y el motor lo consume. Ningún test llama a «enviar el informe»: si el
 * envío colgara de un {@code if} dentro de un caso de uso, estos tests no podrían escribirse así.
 */
class NotificadorAlHisTest extends TestDelMotor {

    private static final String NHC = "74000001";

    @Autowired
    private NotificadorAlHis notificador;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
        HIS.olvidarTodo();
        OutboxDelBackend.vaciar(origenDeDatos());
    }

    @Test
    void un_informe_emitido_llega_al_his_como_oru_r01() {
        Escenario escenario = informeEmitido();

        notificador.unaVuelta();

        assertThat(HIS.recibidos()).hasSize(1);
        String recibido = HIS.recibidos().get(0);

        assertThat(segmento(recibido, "MSH"))
                .as("tipo, estructura de la tabla 0354 y charset declarados")
                .contains("ORU^R01^ORU_R01")
                .contains("2.5.1")
                .contains("8859/1");
        assertThat(segmento(recibido, "PID"))
                .as("el apellido viaja entero y con su eñe, de vuelta hacia el HIS")
                .contains("MUÑOZ DE LA TORRE")
                .contains(NHC + "^^^HISPALIS^MR");

        List<String> obx = segmentos(recibido, "OBX");
        assertThat(obx).hasSize(2);
        assertThat(obx.get(0))
                .as("hacia fuera el código sale en LOINC, con el local en la codificación alternativa de OBX-3")
                .contains("2345-7^Glucosa^LN^GLU^^99HISPALIS")
                .contains("|92|")
                .contains("mg/dL^^UCUM")
                .as("OBX-11=F: solo salen resultados validados, así que aquí `final` sí significa final")
                .endsWith("|F");

        assertThat(consumo(escenario.hechoId())).isEqualTo("ENTREGADO");
    }

    /**
     * Un resultado preliminar no sale hacia el HIS aunque el informe lo citara.
     *
     * <p>El núcleo del laboratorio ya lo impide (ítem 18), y aquí se vuelve a comprobar porque este
     * mensaje <strong>sale del sistema</strong>: un preliminar publicado como {@code F} es una cifra
     * que alguien va a leer como definitiva sin que nadie haya respondido de ella.
     */
    @Test
    void un_informe_cuyos_resultados_no_estan_validados_no_se_manda() {
        Escenario escenario = informeConResultadosPreliminares();

        notificador.unaVuelta();

        assertThat(HIS.recibidos()).isEmpty();
        assertThat(consumo(escenario.hechoId())).isEqualTo("FALLIDO");
    }

    /** Un {@code AE} del HIS no es una entrega buena, y no se marca como tal. */
    @Test
    void si_el_his_rechaza_el_mensaje_el_hecho_no_se_da_por_entregado() {
        Escenario escenario = informeEmitido();
        HIS.responder(AcknowledgmentCode.AE);

        notificador.unaVuelta();

        assertThat(HIS.recibidos()).as("el mensaje sí llegó").hasSize(1);
        assertThat(consumo(escenario.hechoId()))
                .as("pero el HIS dijo que no, y eso no es entregar")
                .isEqualTo("FALLIDO");
    }

    /** Los hechos de otros consumidores se descartan para que el desplazamiento avance. */
    @Test
    void un_hecho_que_no_es_un_informe_se_descarta_sin_mandar_nada() {
        UUID hecho = OutboxDelBackend.apuntar(
                origenDeDatos(),
                "RESULTADO_VALIDADO",
                UUID.randomUUID(),
                Map.of("observationRef", "Observation/" + UUID.randomUUID()));

        notificador.unaVuelta();

        assertThat(HIS.recibidos()).isEmpty();
        assertThat(consumo(hecho)).isEqualTo("DESCARTADO");
    }

    /** Consumido una vez, no se vuelve a mandar: el desplazamiento del motor es suyo y avanza. */
    @Test
    void un_hecho_ya_consumido_no_se_reenvia_en_la_vuelta_siguiente() {
        informeEmitido();

        notificador.unaVuelta();
        notificador.unaVuelta();

        assertThat(HIS.recibidos()).hasSize(1);
    }

    private record Escenario(UUID hechoId, String informeRef) {}

    private Escenario informeEmitido() {
        return sembrarInforme(ObservationStatus.FINAL);
    }

    private Escenario informeConResultadosPreliminares() {
        return sembrarInforme(ObservationStatus.PRELIMINARY);
    }

    private Escenario sembrarInforme(ObservationStatus estado) {
        Patient paciente = new Patient();
        paciente.addIdentifier(
                new Identifier().setSystem(SistemasDeIdentificador.NHC).setValue(NHC));
        paciente.addName().setFamily("MUÑOZ DE LA TORRE").addGiven("Begoña").addGiven("María");
        paciente.setGender(AdministrativeGender.FEMALE);
        paciente.setBirthDateElement(new org.hl7.fhir.r5.model.DateType("1981-03-14"));
        LABORATORIO.sembrar(paciente);
        String pacienteRef = "Patient/" + paciente.getIdElement().getIdPart();

        Observation glucosa = resultado("GLU", "92", "mg/dL", estado, pacienteRef);
        Observation creatinina = resultado("CREA", "0.9", "mg/dL", estado, pacienteRef);
        LABORATORIO.sembrar(glucosa);
        LABORATORIO.sembrar(creatinina);

        DiagnosticReport informe = new DiagnosticReport();
        informe.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        informe.setSubject(new Reference(pacienteRef));
        informe.setIssued(new Date());
        informe.addResult(new Reference("Observation/" + glucosa.getIdElement().getIdPart()));
        informe.addResult(
                new Reference("Observation/" + creatinina.getIdElement().getIdPart()));
        LABORATORIO.sembrar(informe);
        String informeRef = "DiagnosticReport/" + informe.getIdElement().getIdPart();

        UUID hecho = OutboxDelBackend.apuntar(
                origenDeDatos(), "INFORME_EMITIDO", UUID.randomUUID(), Map.of("diagnosticReportRef", informeRef));
        return new Escenario(hecho, informeRef);
    }

    private static Observation resultado(
            String codigo, String valor, String unidad, ObservationStatus estado, String pacienteRef) {
        Observation resultado = new Observation();
        resultado.setStatus(estado);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDelLaboratorio.SYSTEM).setCode(codigo)));
        resultado.setSubject(new Reference(pacienteRef));
        resultado.setValue(new Quantity()
                .setValue(new BigDecimal(valor))
                .setUnit(unidad)
                .setSystem("http://unitsofmeasure.org")
                .setCode(unidad));
        return resultado;
    }

    private String consumo(UUID hechoId) {
        return jdbc.queryForObject(
                "SELECT resultado FROM integracion.hecho_consumido WHERE hecho_id = :id",
                new MapSqlParameterSource("id", hechoId),
                String.class);
    }

    private static String segmento(String mensaje, String nombre) {
        return segmentos(mensaje, nombre).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("El mensaje no trae " + nombre + ": " + mensaje));
    }

    private static List<String> segmentos(String mensaje, String nombre) {
        return List.of(mensaje.split("\r")).stream()
                .filter(segmento -> segmento.startsWith(nombre + "|"))
                .toList();
    }
}
