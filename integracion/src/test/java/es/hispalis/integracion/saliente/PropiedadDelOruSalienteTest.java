package es.hispalis.integracion.saliente;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.NombresEspanoles;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import es.hispalis.integracion.hl7.ContextosHl7;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <strong>Propiedad:</strong> lo que el laboratorio pone en el {@code ORU^R01} que sale hacia el HIS
 * es lo que el HIS lee al parsearlo. Para cualquier informe.
 *
 * <p>Es el otro extremo del {@code decode(encode(x)) == x}: la vuelta. Y no es una formalidad, porque
 * en el camino de salida hay <strong>tres</strong> sitios donde un valor se pierde sin que nadie se
 * entere, y ninguno lanza una excepción al hacerlo:
 *
 * <ul>
 *   <li><strong>El nombre familiar.</strong> Va entero en {@code PID-5.1}, con sus partículas. Si
 *       alguien lo partiera por el espacio —el error de siempre, ahora en la otra dirección— el
 *       mensaje seguiría siendo v2 válido y el HIS archivaría a otra persona.
 *   <li><strong>El identificador y su autoridad.</strong> Cada {@code system} de FHIR se traduce a la
 *       pareja autoridad/tipo de la tabla 0203. Cruzarlas manda el DNI como si fuera el NHC.
 *   <li><strong>El valor numérico.</strong> Un {@code BigDecimal} pasa por {@code toPlainString} y
 *       vuelve como texto: {@code 0.90} y {@code 9E-1} son el mismo número y no la misma cifra en un
 *       informe.
 * </ul>
 *
 * <p>El {@code NotificadorAlHisTest} comprueba esto con un informe escrito a mano. Aquí los informes
 * se generan —nombres españoles, pruebas del catálogo, valores y unidades— y se compara campo a campo
 * lo que entró contra lo que se lee del mensaje ya <strong>reserializado y vuelto a parsear</strong>,
 * que es lo que de verdad va a hacer el receptor.
 */
class PropiedadDelOruSalienteTest extends TestDelMotor {

    private static final long SEMILLA = 20_260_815L;
    private static final int CASOS = 25;

    /** Pruebas cuantitativas del catálogo, con la unidad UCUM que les toca. */
    private static final List<String[]> PRUEBAS = List.of(
            new String[] {"GLU", "mg/dL"},
            new String[] {"CREA", "mg/dL"},
            new String[] {"UREA", "mg/dL"},
            new String[] {"NA", "mmol/L"},
            new String[] {"K", "mmol/L"},
            new String[] {"COLT", "mg/dL"},
            new String[] {"GOT", "U/L"},
            new String[] {"GPT", "U/L"},
            new String[] {"HBA1C", "%"},
            new String[] {"TSH", "uUI/mL"});

    @Autowired
    private TransformadorInformeAOru transformador;

    @BeforeAll
    static void decirConQueSemillaSeCorre() {
        System.out.printf("Propiedad del ORU saliente: semilla %d, %d informes generados.%n", SEMILLA, CASOS);
    }

    static Stream<Arguments> informesGenerados() {
        NombresEspanoles nombres = new NombresEspanoles(SEMILLA);
        Random azar = new Random(SEMILLA);
        return IntStream.range(0, CASOS)
                .mapToObj(i -> Arguments.of(new Caso(
                        "78%06d".formatted(i),
                        nombres.apellidosEnMayusculas(),
                        List.of(nombres.nombreDePila().split("\\^")),
                        azar.nextBoolean() ? AdministrativeGender.FEMALE : AdministrativeGender.MALE,
                        resultadosDe(azar))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("informesGenerados")
    void lo_que_se_pone_en_el_oru_es_lo_que_el_his_lee_al_parsearlo(Caso caso) throws Exception {
        Patient paciente = pacienteDe(caso);
        List<Observation> resultados = observacionesDe(caso);

        Message compuesto = transformador.construir(
                informeDe(paciente, resultados),
                resultados,
                paciente,
                "HISPALIS",
                "LAB_SEVILLA",
                "HIS_VIRGEN",
                "H_VIRGEN_MACARENA",
                "ORU" + caso.nhc());

        // Ida y vuelta de verdad: se serializa como sale por el cable y se vuelve a parsear, que es
        // lo que hará el receptor. Comparar contra el objeto recién construido no probaría nada.
        var parser = ContextosHl7.nuevo().getPipeParser();
        Terser leido = new Terser(parser.parse(parser.encode(compuesto)));

        assertThat(leido.get("/.PATIENT_RESULT/PATIENT/PID-5-1"))
                .as("el nombre familiar va entero: partirlo por el espacio archivaría a otra persona")
                .isEqualTo(caso.apellidos());
        for (int i = 0; i < caso.nombreDePila().size(); i++) {
            String ruta = i == 0 ? "PID-5-2" : "PID-5-3";
            assertThat(leido.get("/.PATIENT_RESULT/PATIENT/" + ruta))
                    .isEqualTo(caso.nombreDePila().get(i));
        }
        assertThat(leido.get("/.PATIENT_RESULT/PATIENT/PID-3-1"))
                .as("el NHC, con su autoridad y su tipo de la tabla 0203")
                .isEqualTo(caso.nhc());
        assertThat(leido.get("/.PATIENT_RESULT/PATIENT/PID-3-4")).isEqualTo("HISPALIS");
        assertThat(leido.get("/.PATIENT_RESULT/PATIENT/PID-3-5")).isEqualTo("MR");
        assertThat(leido.get("/.PATIENT_RESULT/PATIENT/PID-8"))
                .isEqualTo(caso.sexo() == AdministrativeGender.FEMALE ? "F" : "M");

        for (int i = 0; i < caso.resultados().size(); i++) {
            String[] esperado = caso.resultados().get(i);
            String obx = "/.PATIENT_RESULT/ORDER_OBSERVATION/OBSERVATION(%d)/OBX".formatted(i);
            assertThat(leido.get(obx + "-5"))
                    .as("la cifra viaja tal cual: %s no es lo mismo que su notación científica", esperado[2])
                    .isEqualTo(esperado[2]);
            assertThat(leido.get(obx + "-3-4"))
                    .as("el código local viaja SIEMPRE en la codificación alternativa, aunque haya LOINC")
                    .isEqualTo(esperado[0]);
            assertThat(leido.get(obx + "-6-1")).isEqualTo(esperado[1]);
            assertThat(leido.get(obx + "-11")).isEqualTo("F");
        }
    }

    /**
     * Un informe generado.
     *
     * @param resultados cada uno como {@code {código, unidad, valor}}
     */
    record Caso(
            String nhc,
            String apellidos,
            List<String> nombreDePila,
            AdministrativeGender sexo,
            List<String[]> resultados) {

        @Override
        public String toString() {
            return "%s, %d resultado(s)".formatted(apellidos, resultados.size());
        }
    }

    private static List<String[]> resultadosDe(Random azar) {
        int cuantos = 1 + azar.nextInt(4);
        List<String[]> resultados = new ArrayList<>(cuantos);
        for (int i = 0; i < cuantos; i++) {
            String[] prueba = PRUEBAS.get((azar.nextInt(PRUEBAS.size()) + i) % PRUEBAS.size());
            // Dos decimales y a veces un cero final: `0.90` es una cifra distinta de `0.9` en un
            // informe, aunque sean el mismo número, y el redondeo se pierde en silencio.
            String valor = new BigDecimal(azar.nextInt(30_000)).movePointLeft(2).toPlainString();
            resultados.add(new String[] {prueba[0], prueba[1], valor});
        }
        return resultados;
    }

    private static Patient pacienteDe(Caso caso) {
        Patient paciente = new Patient();
        paciente.addIdentifier(
                new Identifier().setSystem(SistemasDeIdentificador.NHC).setValue(caso.nhc()));
        var nombre = paciente.addName().setFamily(caso.apellidos());
        caso.nombreDePila().forEach(nombre::addGiven);
        paciente.setGender(caso.sexo());
        paciente.setBirthDateElement(new org.hl7.fhir.r5.model.DateType("1981-03-14"));
        return paciente;
    }

    private static List<Observation> observacionesDe(Caso caso) {
        List<Observation> observaciones = new ArrayList<>();
        for (String[] resultado : caso.resultados()) {
            Observation observacion = new Observation();
            observacion.setId(java.util.UUID.randomUUID().toString());
            observacion.setStatus(ObservationStatus.FINAL);
            observacion.setCode(new CodeableConcept()
                    .addCoding(new Coding()
                            .setSystem(CatalogoDelLaboratorio.SYSTEM)
                            .setCode(resultado[0])));
            observacion.setValue(new Quantity()
                    .setValue(new BigDecimal(resultado[2]))
                    .setUnit(resultado[1])
                    .setSystem("http://unitsofmeasure.org")
                    .setCode(resultado[1]));
            observaciones.add(observacion);
        }
        return observaciones;
    }

    private static DiagnosticReport informeDe(Patient paciente, List<Observation> resultados) {
        DiagnosticReport informe = new DiagnosticReport();
        informe.setId(java.util.UUID.randomUUID().toString());
        informe.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        informe.setSubject(new Reference("Patient/" + paciente.getIdElement().getIdPart()));
        informe.setIssued(new Date());
        resultados.forEach(resultado -> informe.addResult(
                new Reference("Observation/" + resultado.getIdElement().getIdPart())));
        return informe;
    }
}
