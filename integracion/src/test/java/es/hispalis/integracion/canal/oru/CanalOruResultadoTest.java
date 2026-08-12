package es.hispalis.integracion.canal.oru;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.LaboratorioDePrueba;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.arnes.Volcado;
import es.hispalis.integracion.fhir.ResultadosCualitativos;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.Specimen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * El canal de resultados entrantes: {@code ORU^R01} del analizador → {@code Observation} preliminar.
 */
class CanalOruResultadoTest extends TestDelMotor {

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(72_000_000);

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    @Test
    void un_resultado_del_analizador_entra_como_PRELIMINAR() {
        Escenario escenario = prepararMuestra();

        String acuse = elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(), escenario.nhc(), escenario.acceso(), "LN", "2345-7|NM|92|mg/dL"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");
        assertThat(LABORATORIO.guardados(Observation.class)).singleElement().satisfies(resultado -> {
            assertThat(resultado.getStatus())
                    .as("el analizador mide, no valida: `final` es la firma de un facultativo")
                    .isEqualTo(ObservationStatus.PRELIMINARY);
            assertThat(LaboratorioDePrueba.codigoDe(resultado)).isEqualTo("GLU");
            assertThat(resultado.getValueQuantity().getValue().doubleValue()).isEqualTo(92.0);
            assertThat(resultado.getValueQuantity().getCode()).isEqualTo("mg/dL");
            assertThat(resultado.getSpecimen().getReference()).isEqualTo(escenario.especimenRef());
            assertThat(resultado.hasEffectiveDateTimeType())
                    .as("OBX-14 dice cuándo se midió, y se conserva")
                    .isTrue();
        });

        Volcado.escribir(
                "4-resultado-desde-oru",
                LABORATORIO.guardados(Observation.class).get(0));
    }

    /**
     * El analizador no puede acabar en {@code Observation.performer}.
     *
     * <p>En R5 ese elemento no admite {@code Device}, y el servidor de verdad rechaza el recurso
     * entero con un 422. Este test existe porque el doble de la API <strong>no valida referencias</strong>
     * y dejó pasar el mapeo hasta que se levantaron los procesos de verdad: aquí no se comprueba que
     * el laboratorio lo acepte, se comprueba que el motor no lo mande.
     */
    @Test
    void el_resultado_no_apunta_al_analizador_como_si_fuera_un_profesional() {
        Escenario escenario = prepararMuestra();

        elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(), escenario.nhc(), escenario.acceso(), "LN", "2345-7|NM|92|mg/dL"));

        assertThat(LABORATORIO.guardados(Observation.class)).singleElement().satisfies(resultado -> assertThat(
                        resultado.hasPerformer())
                .as("`Device` no cabe en `performer` en R5, y el aparato no es un facultativo")
                .isFalse());
    }

    /**
     * Un cualitativo <strong>codificado</strong> llega codificado, y de eso depende que se declare.
     *
     * <p>{@code CE}/{@code CWE} en {@code OBX-2} significa que el analizador mandó un
     * <em>concepto</em>, no una frase. El motor lo pasaba a {@code valueString} igual que un
     * {@code ST}, y ahí se perdía el código: el laboratorio guardaba «POS» como descripción, la regla
     * de declaración obligatoria compara <strong>códigos</strong> y nunca veía un positivo. Resultado
     * — <strong>una Legionella positiva que entra por el analizador no se declaraba jamás</strong>, y
     * el circuito entero del ítem 48 solo funcionaba escribiendo por la API.
     *
     * <p>Apareció recorriendo el circuito del ítem 51 contra el {@code compose}. Los tests no lo
     * veían porque el único cualitativo que probaban era texto libre —{@code ST|Negativo}—, que sí
     * debe guardarse como texto: eso no es un código, es una descripción. Está en {@code adr-0034}.
     */
    @Test
    void un_cualitativo_codificado_conserva_el_codigo_y_su_sistema() {
        Escenario escenario = prepararMuestra();

        String acuse = elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(),
                escenario.nhc(),
                escenario.acceso(),
                "99HISPALIS",
                "LEGIOAG|CWE|POS^Positivo^99HISPCUAL|"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");
        assertThat(LABORATORIO.guardados(Observation.class)).singleElement().satisfies(resultado -> {
            assertThat(resultado.getValueCodeableConcept().getCodingFirstRep().getCode())
                    .as("el código es lo que dispara la declaración obligatoria: no puede perderse")
                    .isEqualTo("POS");
            assertThat(resultado.getValueCodeableConcept().getCodingFirstRep().getSystem())
                    .as("un código sin `system` no es un código, es una cadena con suerte")
                    .isEqualTo(ResultadosCualitativos.SYSTEM);
        });
    }

    /**
     * Y un vocabulario que el motor no conoce no se inventa: se guarda como lo que es, texto.
     *
     * <p>Control negativo del anterior. Un serotipo en el diccionario del aparato es un resultado
     * legítimo que este laboratorio no ha codificado; ponerle un {@code system} nuestro afirmaría una
     * equivalencia que nadie ha declarado, y descartarlo tiraría el dato.
     */
    @Test
    void un_codigo_de_un_vocabulario_desconocido_se_guarda_como_texto() {
        Escenario escenario = prepararMuestra();

        elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(),
                escenario.nhc(),
                escenario.acceso(),
                "99HISPALIS",
                "LEGIOAG|CWE|SG7^Serogrupo 7^99AU5800|"));

        assertThat(LABORATORIO.guardados(Observation.class))
                .singleElement()
                .extracting(resultado -> resultado.getValueStringType().getValue())
                .isEqualTo("SG7");
    }

    /** Un resultado cualitativo: la mitad de los mapeos ingenuos se rompen aquí. */
    @Test
    void un_resultado_de_texto_no_se_intenta_convertir_a_numero() {
        Escenario escenario = prepararMuestra();

        String acuse = elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(), escenario.nhc(), escenario.acceso(), "99HISPALIS", "LEGIOAG|ST|Negativo|"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");
        assertThat(LABORATORIO.guardados(Observation.class))
                .singleElement()
                .extracting(resultado -> resultado.getValueStringType().getValue())
                .isEqualTo("Negativo");
    }

    /**
     * La comprobación que evita multiplicar una creatinina por 88.
     *
     * <p>El analizador informa en {@code umol/L} y el laboratorio publica esa prueba en {@code mg/dL}.
     * Las dos cifras son correctas por separado; guardar una como si fuera la otra no lo es, y nada en
     * el recurso permitiría notarlo después.
     */
    @Test
    void una_unidad_que_no_es_la_del_catalogo_se_rechaza_en_vez_de_convertirse() {
        Escenario escenario = prepararMuestra();

        String acuse = elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(), escenario.nhc(), escenario.acceso(), "99HISPALIS", "CREA|NM|88|umol/L"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse).contains("umol/L").contains("mg/dL");
        assertThat(LABORATORIO.guardados(Observation.class)).isEmpty();
    }

    @Test
    void una_prueba_que_el_catalogo_no_conoce_se_rechaza() {
        Escenario escenario = prepararMuestra();

        String acuse = elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(), escenario.nhc(), escenario.acceso(), "LN", "99999-9|NM|1|mg/dL"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse).contains("99999-9");
        assertThat(LABORATORIO.guardados(Observation.class)).isEmpty();
    }

    @Test
    void un_resultado_de_una_muestra_que_no_existe_no_se_inventa_la_muestra() {
        String nhc = registrarPaciente();

        String acuse =
                elHis().enviar(MensajesDePrueba.oru("ORU" + nhc, nhc, "NOEXISTE", "99HISPALIS", "GLU|NM|92|mg/dL"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse).contains("NOEXISTE");
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    /**
     * {@code OBX-11=C} es una corrección de un resultado anterior, y eso es otra operación clínica.
     *
     * <p>Tragársela como si fuera un resultado nuevo dejaría dos cifras de la misma prueba sobre la
     * misma muestra, sin decir cuál manda.
     */
    @Test
    void una_correccion_del_analizador_se_rechaza_en_vez_de_colarse_como_resultado_nuevo() {
        Escenario escenario = prepararMuestra();

        String acuse = elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(), escenario.nhc(), escenario.acceso(), "99HISPALIS", "GLU|NM|92|mg/dL|C"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(acuse).contains("OBX-11=C");
        assertThat(LABORATORIO.guardados(Observation.class)).isEmpty();
    }

    /** Reenviar el mismo resultado con otro {@code MSH-10} no crea una segunda cifra. */
    @Test
    void el_mismo_resultado_con_otro_control_id_no_se_escribe_dos_veces() {
        Escenario escenario = prepararMuestra();
        String primero = "ORU1" + escenario.nhc();
        String segundo = "ORU2" + escenario.nhc();

        elHis().enviar(MensajesDePrueba.oru(
                primero, escenario.nhc(), escenario.acceso(), "99HISPALIS", "GLU|NM|92|mg/dL"));
        String acuse = elHis().enviar(MensajesDePrueba.oru(
                segundo, escenario.nhc(), escenario.acceso(), "99HISPALIS", "GLU|NM|92|mg/dL"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AA");
        assertThat(LABORATORIO.guardados(Observation.class)).hasSize(1);
    }

    @Test
    void un_panel_con_varios_obx_produce_un_resultado_por_cada_uno() {
        Escenario escenario = prepararMuestra();

        String acuse = elHis().enviar(MensajesDePrueba.oru(
                "ORU" + escenario.nhc(),
                escenario.nhc(),
                escenario.acceso(),
                "99HISPALIS",
                "GLU|NM|92|mg/dL",
                "CREA|NM|0.9|mg/dL",
                "K|NM|4.2|mmol/L"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");
        assertThat(LABORATORIO.guardados(Observation.class))
                .hasSize(3)
                .extracting(LaboratorioDePrueba::codigoDe)
                .containsExactlyInAnyOrder("GLU", "CREA", "K");
    }

    /** El resultado se ata a la línea del volante cuando el analizador devuelve su número. */
    @Test
    void el_resultado_se_enlaza_con_la_linea_del_volante_si_el_analizador_devuelve_su_numero() {
        Escenario escenario = prepararMuestra();
        elHis().enviar(MensajesDePrueba.oml(
                "OML" + escenario.nhc(), escenario.nhc(), "P-CON-LINEA", escenario.acceso(), "119364003", "GLU"));
        String lineaRef = "ServiceRequest/"
                + LABORATORIO
                        .guardados(org.hl7.fhir.r5.model.ServiceRequest.class)
                        .get(0)
                        .getIdElement()
                        .getIdPart();

        elHis().enviar(MensajesDePrueba.oruConVolante(
                "ORU" + escenario.nhc(),
                escenario.nhc(),
                escenario.acceso(),
                "P-CON-LINEA",
                "99HISPALIS",
                "GLU|NM|92|mg/dL"));

        assertThat(LABORATORIO.guardados(Observation.class))
                .singleElement()
                .extracting(resultado -> resultado.getBasedOnFirstRep().getReference())
                .isEqualTo(lineaRef);
    }

    private record Escenario(String nhc, String acceso, String especimenRef) {}

    private static Escenario prepararMuestra() {
        String nhc = registrarPaciente();
        String acceso = "ACC" + nhc;
        Specimen muestra = new Specimen();
        muestra.getAccessionIdentifier().setValue(acceso);
        muestra.setSubject(new Reference("Patient/" + nhc));
        muestra.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        LABORATORIO.sembrar(muestra);
        return new Escenario(nhc, acceso, "Specimen/" + muestra.getIdElement().getIdPart());
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

    private static String codigoDeAcuse(String acuse) {
        return List.of(acuse.split("\r")).stream()
                .filter(segmento -> segmento.startsWith("MSA|"))
                .map(segmento -> segmento.split("\\|")[1])
                .findFirst()
                .orElseThrow(() -> new AssertionError("El acuse no trae MSA: " + acuse));
    }
}
