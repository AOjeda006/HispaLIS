package es.hispalis.integracion.canal.adt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.v251.message.ADT_A01;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El mapeo, probado <strong>sin listener, sin base de datos y sin red</strong>.
 *
 * <p>El canal completo se prueba de extremo a extremo en {@code CanalAdtPacienteTest}, y hace falta.
 * Este es el otro test, el que permite discutir el mapeo campo a campo sin levantar nada — que es
 * como se revisa un mapeo de verdad, con la tabla del estándar al lado.
 */
class TransformadorAdtAPacienteTest {

    private static final HapiContext CONTEXTO = contexto();

    private final TransformadorAdtAPaciente transformador = new TransformadorAdtAPaciente();

    /**
     * El caso que hunde a las tuberías v2 españolas. «De la Torre Gómez» son <strong>dos</strong>
     * apellidos y cuatro palabras; «Fernández de Córdoba Ruiz» son dos y cinco. Ningún heurístico
     * sobre espacios acierta, y equivocarse aquí es atribuir un resultado a otra persona.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MUÑOZ DE LA TORRE", "FERNÁNDEZ DE CÓRDOBA RUIZ", "PEÑA ÁLVAREZ", "DE LA TORRE GÓMEZ"})
    void el_apellido_llega_entero_desde_pid_5(String apellidos) throws Exception {
        Patient paciente = transformador.aPatient(pidDe(apellidos, "Begoña^María"));

        assertThat(paciente.getNameFirstRep().getFamily()).isEqualTo(apellidos);
    }

    /**
     * V2.5.1 no tiene sitio estándar para «primer apellido» y «segundo apellido»: los componentes de
     * {@code FN} describen el apellido propio y el del cónyuge, que es otra cosa. Sin acuerdo escrito
     * con el emisor, el motor <strong>no inventa la descomposición</strong>.
     */
    @Test
    void los_apellidos_no_se_descomponen_sin_que_el_emisor_los_mande_descompuestos() throws Exception {
        Patient paciente = transformador.aPatient(pidDe(MensajesDePrueba.MUNOZ, "Begoña^María"));

        assertThat(paciente.getNameFirstRep().getFamilyElement().getExtension())
                .as("una descomposición deducida es un dato inventado sobre la identidad de alguien")
                .isEmpty();
    }

    @Test
    void cada_identificador_va_a_su_system_segun_su_tipo_y_no_segun_su_posicion() throws Exception {
        Patient paciente = transformador.aPatient(pidDe(MensajesDePrueba.PENA, "Rocío^Ana"));

        assertThat(paciente.getIdentifier())
                .extracting(identificador -> identificador.getSystem() + "=" + identificador.getValue())
                .containsExactlyInAnyOrder(
                        SistemasDeIdentificador.NHC + "=70000001",
                        SistemasDeIdentificador.DNI_NIE + "=12345678Z",
                        SistemasDeIdentificador.CIP_AUTONOMICO + "=AN0123456789");
    }

    @Test
    void sin_nhc_no_hay_paciente_que_registrar() throws Exception {
        ADT_A01 sinNhc = (ADT_A01) CONTEXTO.getPipeParser()
                .parse(MensajesDePrueba.adt("A01", "MSG1", "70000001", MensajesDePrueba.PENA, "Rocío^Ana", "8859/1")
                        .replace("^^^HISPALIS^MR", "^^^HISPALIS^XX"));

        assertThatThrownBy(() -> transformador.aPatient(sinNhc.getPID()))
                .isInstanceOf(TransformadorAdtAPaciente.DemografiaIncompleta.class)
                .hasMessageContaining("MR");
    }

    @Test
    void la_fecha_de_nacimiento_pierde_la_hora_pero_no_el_dia() throws Exception {
        Patient paciente = transformador.aPatient(pidDe(MensajesDePrueba.MUNOZ, "Begoña^María"));

        assertThat(paciente.getBirthDateElement().asStringValue()).isEqualTo("1981-03-14");
    }

    private static ca.uhn.hl7v2.model.v251.segment.PID pidDe(String apellidos, String nombreDePila) throws Exception {
        ADT_A01 mensaje = (ADT_A01) CONTEXTO.getPipeParser()
                .parse(MensajesDePrueba.adt("A01", "MSG1", "70000001", apellidos, nombreDePila, "UNICODE UTF-8"));
        return mensaje.getPID();
    }

    private static HapiContext contexto() {
        DefaultHapiContext contexto = new DefaultHapiContext();
        contexto.setModelClassFactory(new CanonicalModelClassFactory("2.5.1"));
        contexto.setValidationContext(ValidationContextFactory.noValidation());
        return contexto;
    }
}
