package es.hispalis.integracion.canal.oml;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.LaboratorioDePrueba;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.arnes.Volcado;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * El canal de peticiones, de extremo a extremo: {@code OML^O21} entra por MLLP y salen las líneas del
 * volante y su muestra, escritas <strong>una a una</strong> por la API FHIR (D22).
 */
class CanalOmlPeticionTest extends TestDelMotor {

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(71_000_000);

    /** El tipo de muestra más común del laboratorio, del {@code ValueSet} de la guía: suero. */
    private static final String SUERO = "119364003";

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    @Test
    void un_volante_con_dos_pruebas_produce_dos_lineas_y_una_muestra() {
        String nhc = registrarPaciente();

        String acuse = elHis().enviar(
                        MensajesDePrueba.oml("OML" + nhc, nhc, "P20260806-A1", "ACC" + nhc, SUERO, "GLU", "CREA"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");

        List<ServiceRequest> lineas = LABORATORIO.guardados(ServiceRequest.class);
        assertThat(lineas).hasSize(2);
        assertThat(lineas)
                .as("las dos líneas comparten volante: es lo que las agrupa")
                .allSatisfy(
                        linea -> assertThat(linea.getRequisition().getValue()).isEqualTo("P20260806-A1"));
        assertThat(lineas).extracting(LaboratorioDePrueba::codigoDe).containsExactlyInAnyOrder("GLU", "CREA");
        assertThat(lineas).as("el peticionario sale de ORC-12, no se inventa").allSatisfy(linea -> assertThat(
                        linea.getRequester().getReference())
                .isEqualTo("Practitioner/COL12345"));

        assertThat(LABORATORIO.guardados(Specimen.class))
                .as("el mismo tubo cuelga de los dos OBR y se registra UNA vez")
                .singleElement()
                .satisfies(muestra -> {
                    assertThat(muestra.getAccessionIdentifier().getValue()).isEqualTo("ACC" + nhc);
                    assertThat(muestra.getType().getCodingFirstRep().getCode()).isEqualTo(SUERO);
                    assertThat(muestra.getStatus()).isEqualTo(Specimen.SpecimenStatus.AVAILABLE);
                });

        // Que el canal funcione y que lo que produce sea CONFORME son dos cosas distintas, y la
        // segunda solo la puede afirmar el validador oficial. La CI lo pasa sobre esta carpeta.
        Volcado.escribir("2-linea-desde-oml", lineas.get(0));
        Volcado.escribir(
                "3-muestra-desde-oml", LABORATORIO.guardados(Specimen.class).get(0));
    }

    /**
     * El HIS pide en LOINC y el laboratorio guarda en su dialecto.
     *
     * <p>La traducción la hace el {@code ConceptMap} que publica la guía, leído al revés — no un
     * {@code Map<String,String>} dentro del motor (invariante 4).
     */
    @Test
    void un_volante_que_pide_en_loinc_se_traduce_al_catalogo_del_laboratorio() {
        String nhc = registrarPaciente();

        String acuse = elHis().enviar(
                        MensajesDePrueba.omlEn("OML" + nhc, nhc, "P20260806-B1", "ACC" + nhc, SUERO, "LN", "2345-7"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AA");
        assertThat(LABORATORIO.guardados(ServiceRequest.class))
                .singleElement()
                .extracting(LaboratorioDePrueba::codigoDe)
                .as("2345-7 es la glucosa masa/volumen; en el catálogo local es GLU")
                .isEqualTo("GLU");
    }

    @Test
    void una_prueba_que_no_esta_en_el_catalogo_se_rechaza_sin_escribir_nada() {
        String nhc = registrarPaciente();

        String acuse = elHis().enviar(
                        MensajesDePrueba.oml("OML" + nhc, nhc, "P20260806-C1", "ACC" + nhc, SUERO, "GLU", "NOEXISTE"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse).contains("NOEXISTE");
        assertThat(LABORATORIO.escrituras())
                .as("el mapeo falla ANTES de escribir: nada de medio volante")
                .isZero();
    }

    @Test
    void un_tipo_de_muestra_que_el_laboratorio_no_acepta_se_rechaza() {
        String nhc = registrarPaciente();

        String acuse =
                elHis().enviar(MensajesDePrueba.oml("OML" + nhc, nhc, "P20260806-D1", "ACC" + nhc, "000000", "GLU"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse).contains("000000");
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    /** Un {@code OML} no da de alta: la demografía entra por el {@code ADT}, no por un volante. */
    @Test
    void un_volante_de_un_paciente_que_no_existe_no_lo_crea() {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());

        String acuse =
                elHis().enviar(MensajesDePrueba.oml("OML" + nhc, nhc, "P20260806-E1", "ACC" + nhc, SUERO, "GLU"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse)
                .as("el motivo dice qué hacer, y sin circunflejos: HAPI los escaparía como \\S\\ en el ERR")
                .contains("mande primero el ADT A01");
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    @Test
    void un_volante_sin_numero_en_orc_4_se_rechaza() {
        String nhc = registrarPaciente();

        String acuse = elHis().enviar(MensajesDePrueba.oml("OML" + nhc, nhc, "", "ACC" + nhc, SUERO, "GLU"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse).contains("ORC-4");
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    /**
     * La idempotencia por la clave de negocio, que es lo que sostiene D22.
     *
     * <p>Se manda el mismo volante con OTRO {@code MSH-10}, así que la deduplicación del almacén
     * <strong>no</strong> lo para: lo que tiene que pararlo es la búsqueda que hace el canal antes de
     * cada escritura. Sin eso, el reproceso duplicaría.
     */
    @Test
    void el_mismo_volante_con_otro_control_id_no_duplica_lineas_ni_muestras() {
        String nhc = registrarPaciente();
        String volante = "P20260806-F1";
        String acceso = "ACC" + nhc;

        elHis().enviar(MensajesDePrueba.oml("OML1" + nhc, nhc, volante, acceso, SUERO, "GLU", "CREA"));
        String segundo = elHis().enviar(MensajesDePrueba.oml("OML2" + nhc, nhc, volante, acceso, SUERO, "GLU", "CREA"));

        assertThat(codigoDeAcuse(segundo)).isEqualTo("AA");
        assertThat(LABORATORIO.guardados(ServiceRequest.class)).hasSize(2);
        assertThat(LABORATORIO.guardados(Specimen.class)).hasSize(1);
    }

    /** Dos volantes distintos del mismo paciente sí producen líneas distintas. */
    @Test
    void dos_volantes_distintos_no_se_confunden_entre_si() {
        String nhc = registrarPaciente();

        elHis().enviar(MensajesDePrueba.oml("OMLX" + nhc, nhc, "P-UNO", "ACC1" + nhc, SUERO, "GLU"));
        elHis().enviar(MensajesDePrueba.oml("OMLY" + nhc, nhc, "P-DOS", "ACC2" + nhc, SUERO, "GLU"));

        assertThat(LABORATORIO.guardados(ServiceRequest.class))
                .as("la clave es volante + prueba, no la prueba a secas")
                .hasSize(2);
    }

    /** Siembra un paciente en el laboratorio y devuelve su NHC. */
    private static String registrarPaciente() {
        String nhc = String.valueOf(SIGUIENTE.incrementAndGet());
        Patient paciente = new Patient();
        paciente.addIdentifier(new Identifier()
                .setSystem(es.hispalis.integracion.fhir.SistemasDeIdentificador.NHC)
                .setValue(nhc));
        paciente.addName().setFamily(MensajesDePrueba.MUNOZ).addGiven("Begoña");
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
