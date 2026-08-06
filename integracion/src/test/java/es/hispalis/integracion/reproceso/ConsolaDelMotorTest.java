package es.hispalis.integracion.reproceso;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * La consola del motor, por HTTP: la bandeja de errores se ve y el reproceso se lanza.
 *
 * <p>Es lo que D11 dice que se pierde al no usar Mirth: la consola desde la que un operador mira qué
 * falló y lo vuelve a lanzar. Se prueba <strong>por HTTP</strong> y no llamando al servicio porque lo
 * que se está comprobando es justamente que exista esa puerta.
 */
class ConsolaDelMotorTest extends TestDelMotor {

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(75_000_000);
    private static final String SUERO = "119364003";

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    @Test
    void la_bandeja_muestra_lo_que_fallo_y_no_el_mensaje_original() {
        String nhc = registrarPaciente();
        String control = "CONSOLA" + nhc;
        elHis().enviar(MensajesDePrueba.oml(control, nhc, "P-CONSOLA", "ACC" + nhc, SUERO, "NOEXISTE"));

        List<?> bandeja = http.getForObject("/motor/dlq", List.class);

        assertThat(bandeja).isNotEmpty();
        String comoTexto = http.getForObject("/motor/dlq", String.class);
        assertThat(comoTexto)
                .contains(control)
                .contains("OML^O21")
                .contains("NOEXISTE")
                .as("el NHC va en el cuerpo porque sin él no se sabe a quién afecta el error")
                .contains(nhc);
        assertThat(comoTexto)
                .as("pero el mensaje v2 entero NO: una consola de operación no es sitio para un volcado clínico")
                .doesNotContain("MSH|");
    }

    @Test
    void el_reproceso_por_http_completa_lo_que_faltaba() {
        String nhc = registrarPaciente();
        String control = "CONSOLAOK" + nhc;
        LABORATORIO.fallarLaProximaEscrituraDe("Specimen");
        elHis().enviar(MensajesDePrueba.oml(control, nhc, "P-CONSOLA-OK", "ACC" + nhc, SUERO, "GLU"));
        assertThat(LABORATORIO.guardados(Specimen.class)).isEmpty();

        String cuerpo = http.getForObject("/motor/dlq", String.class);
        UUID id = UUID.fromString(cuerpo.split("\"id\":\"")[1].split("\"")[0]);

        ResponseEntity<String> respuesta = http.postForEntity("/motor/dlq/{id}/reproceso", null, String.class, id);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respuesta.getBody()).contains("\"aplicado\":true");
        assertThat(LABORATORIO.guardados(Specimen.class)).hasSize(1);
        assertThat(LABORATORIO.guardados(ServiceRequest.class)).hasSize(1);
    }

    @Test
    void reprocesar_un_identificador_que_no_existe_devuelve_404() {
        ResponseEntity<String> respuesta =
                http.postForEntity("/motor/dlq/{id}/reproceso", null, String.class, UUID.randomUUID());

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static String registrarPaciente() {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());
        Patient paciente = new Patient();
        paciente.addIdentifier(
                new Identifier().setSystem(SistemasDeIdentificador.NHC).setValue(nhc));
        paciente.addName().setFamily(MensajesDePrueba.PENA).addGiven("Rocío");
        LABORATORIO.sembrar(paciente);
        return nhc;
    }
}
