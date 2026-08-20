package es.hispalis.backend.aplicacion.reconciliacion;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.aplicacion.reconciliacion.Divergencia.Clase;
import es.hispalis.backend.dominio.especimen.RepositorioDeEspecimenes;
import es.hispalis.backend.dominio.informe.Informe;
import es.hispalis.backend.dominio.informe.RepositorioDeInformes;
import es.hispalis.backend.dominio.paciente.RepositorioDePacientes;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * El reconciliador sobre el laboratorio <strong>entero</strong>, y con volumen.
 *
 * <p>{@link ReconciliadorTest} lo ejercita acotado a un paciente, que es como se usa para recuperar de
 * un incidente concreto. El barrido completo —{@code ejecutar(null, …)}— es otro camino de código y
 * hasta aquí no lo recorría ningún test: en particular {@code pacientesSinAgregado}, que solo se
 * ejecuta sin acotar y es el único sitio donde se detecta un {@code Patient} publicado del que ya no
 * responde nadie. Un reconciliador que nunca ha encontrado una divergencia no se sabe si sabe
 * encontrarlas, y una rama que ningún test recorre no se sabe si funciona.
 *
 * <h2>El corpus</h2>
 *
 * <p>Se escribe <strong>por la API</strong>, recorriendo el circuito entero por paciente: petición,
 * muestra, resultado, firma e informe. No se siembra por detrás a propósito — lo que se quiere medir
 * es la proyección que produce el camino de escritura de verdad (D3, §9), no una montada a mano.
 *
 * <p>Los apellidos vienen de una lista con lo que de verdad llega a un laboratorio de Sevilla: dobles,
 * con partículas, con {@code Ñ}, con tildes y con {@code ç}. La variedad no es decorativa: el
 * reconciliador compara la serialización del agregado contra la del recurso publicado, así que
 * cualquier inestabilidad de codificación aparecería aquí como una divergencia falsa sobre un recurso
 * recién escrito.
 *
 * <p>Sesenta pacientes por defecto, que es lo que aguanta una CI sin convertirse en el trabajo más
 * largo del repositorio. Para una tanda de verdad,
 * {@code -Dhispalis.reconciliacion.pacientes=300}.
 *
 * <h2>Por qué las afirmaciones son relativas</h2>
 *
 * <p>El PostgreSQL embebido es <strong>uno para toda la ejecución</strong> y estos tests no van en
 * transacción: cuando esta clase corre, el laboratorio ya tiene dentro lo que hayan dejado las demás,
 * y alguna deja divergencias a propósito. Por eso se mide contra una foto tomada <em>antes</em> de
 * escribir el corpus: lo que importa no es cuántas divergencias hay en total, sino que el corpus nuevo
 * no añada ninguna y que las provocadas aparezcan todas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class ReconciliacionDelLaboratorioEnteroTest extends TestDeIntegracion {

    /** Cuántos pacientes recorren el circuito. Sube con {@code -Dhispalis.reconciliacion.pacientes=…}. */
    private static final int PACIENTES = Integer.getInteger("hispalis.reconciliacion.pacientes", 60);

    /**
     * Apellidos españoles de verdad, incluidos los que rompen cosas.
     *
     * <p>{@code MUÑOZ}, {@code ÁLVAREZ} y {@code PEÑA} son obligatorios en todo lo que toque nombres.
     * Las partículas están porque el nombre familiar va entero en {@code family} y nunca partido por
     * el espacio, y {@code Vicenç} porque la {@code ç} sale del juego de caracteres más estrecho.
     */
    private static final List<String> APELLIDOS = List.of(
            "Muñoz Peñalver",
            "Álvarez de la Peña",
            "de la Torre Gómez",
            "Fernández de Córdoba Ruiz",
            "Núñez Ibáñez",
            "Ordóñez Sanchís",
            "Vicenç Mataró",
            "del Río Aguilera",
            "Sáez de Heredia",
            "Peña Muñoz");

    private final FhirContext contexto = FhirContext.forR5();
    private final SystemRequestDetails comoElSistema = new SystemRequestDetails();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Reconciliador reconciliador;

    @Autowired
    private DaoRegistry daos;

    @Autowired
    private RepositorioDePacientes pacientes;

    // Los cuatro que faltaban, y solo para el diagnóstico: cuando una referencia aparece donde no
    // se la esperaba, lo primero que hay que saber es si el dominio la conoce.
    @Autowired
    private RepositorioDePeticiones peticiones;

    @Autowired
    private RepositorioDeEspecimenes especimenes;

    @Autowired
    private RepositorioDeResultados resultados;

    @Autowired
    private RepositorioDeInformes informes;

    private CircuitoDePrueba circuito;

    /** Las divergencias que ya había antes de que esta clase escribiera nada. */
    private Set<String> preexistentes;

    private List<Escenario> corpus;

    /** Lo que este test rompe a propósito, para que el diagnóstico no lo confunda con un hallazgo. */
    private final Set<String> plantadas = new LinkedHashSet<>();

    @BeforeAll
    void escribirElCorpusPorLaApi() {
        circuito = new CircuitoDePrueba(rest, contexto);
        preexistentes = referenciasDe(reconciliador.ejecutar(null, false));

        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        corpus = new ArrayList<>(PACIENTES);

        long empezo = System.nanoTime();
        for (int i = 0; i < PACIENTES; i++) {
            corpus.add(recorrerElCircuito(laboratorio, APELLIDOS.get(i % APELLIDOS.size())));
        }
        System.out.printf(
                "Corpus escrito por la API: %d pacientes con su petición, muestra, resultado firmado e "
                        + "informe, en %s.%n",
                PACIENTES, enSegundos(System.nanoTime() - empezo));
    }

    /**
     * Lo primero que hay que saber de un reconciliador con volumen: que no inventa.
     *
     * <p>Un falso positivo aquí sería peor que no tenerlo, porque el modo reparación reescribiría
     * recursos que estaban bien y caducaría los {@code ETag} de sus clientes sin motivo.
     */
    @Test
    @Order(1)
    void el_barrido_del_laboratorio_entero_no_inventa_divergencias() {
        long empezo = System.nanoTime();
        InformeDeReconciliacion informe = reconciliador.ejecutar(null, false);
        Duration tardo = Duration.ofNanos(System.nanoTime() - empezo);

        int enElLaboratorio = pacientes.todasLasIdentidades().size();
        System.out.printf(
                "Barrido completo: %d pacientes revisados en %s (%.1f ms por paciente), %d divergencia(s).%n",
                enElLaboratorio,
                enSegundos(tardo.toNanos()),
                tardo.toNanos() / 1_000_000.0 / enElLaboratorio,
                informe.divergencias().size());

        Set<String> encontradas = referenciasDe(informe);
        assertThat(encontradas)
                .as(
                        "la proyección se escribe en la transacción del dominio: %d pacientes recién "
                                + "escritos por la API tienen que cuadrar todos%s",
                        PACIENTES, deDondeSalen(informe, loQueSobraDe(encontradas, preexistentes)))
                .isEqualTo(preexistentes);
    }

    /**
     * Las cuatro formas de divergir, repartidas por el corpus, y el barrido que las encuentra todas.
     *
     * <p>La cuarta —un {@code Patient} publicado sin paciente detrás— es la que solo aparece sin
     * acotar, y la razón de que este test exista: acotado a una persona, el reconciliador no puede
     * saber que ese recurso sobra, porque nadie le ha dado su identidad para preguntar por él.
     *
     * <p>Van en tres rincones distintos del corpus a propósito: al principio, por el medio y al final.
     * Un barrido que se quedara corto —una búsqueda paginada, un límite de la DAO— repararía los
     * primeros y dejaría los últimos, y con una sola divergencia al principio no se notaría.
     */
    @Test
    @Order(2)
    void una_divergencia_en_cualquier_rincon_aparece_en_el_barrido_y_se_repara() {
        Escenario primero = corpus.get(0);
        Escenario porElMedio = corpus.get(corpus.size() / 2);
        Escenario ultimo = corpus.get(corpus.size() - 1);

        borrarDeLaProyeccion("DiagnosticReport", primero.informeId());
        alterarLaCifraEnLaProyeccion(porElMedio.resultadoId(), new BigDecimal("999"));
        UUID resultadoDeNadie = publicarUnResultadoSinAgregado(ultimo);
        UUID pacienteDeNadie = publicarUnPacienteSinAgregado();

        Set<String> provocadas = new LinkedHashSet<>(List.of(
                "DiagnosticReport/" + primero.informeId(),
                "Observation/" + porElMedio.resultadoId(),
                "Observation/" + resultadoDeNadie,
                "Patient/" + pacienteDeNadie));
        plantadas.addAll(provocadas);

        long empezo = System.nanoTime();
        InformeDeReconciliacion revision = reconciliador.ejecutar(null, false);
        System.out.printf("Barrido con divergencias provocadas: %s.%n", enSegundos(System.nanoTime() - empezo));

        Set<String> nuevas = loNuevoEn(revision);
        assertThat(nuevas)
                .as(
                        "una divergencia provocada que el barrido no ve es un reconciliador que no sirve%s",
                        deDondeSalen(revision, loQueSobraDe(nuevas, provocadas)))
                .containsExactlyInAnyOrderElementsOf(provocadas);
        assertThat(revision.de(Clase.HUERFANO))
                .as("el paciente sin agregado detrás solo lo caza el recorrido completo")
                .extracting(Divergencia::referencia)
                .contains("Patient/" + pacienteDeNadie);

        empezo = System.nanoTime();
        InformeDeReconciliacion reparacion = reconciliador.ejecutar(null, true);
        System.out.printf(
                "Reparación del laboratorio entero: %d divergencia(s) en %s.%n",
                reparacion.divergencias().size(), enSegundos(System.nanoTime() - empezo));

        assertThat(reparacion.aplicado()).isTrue();
        assertThat(cifraEnLaProyeccion(porElMedio.resultadoId()))
                .as("la cifra vuelve a ser la del dominio")
                .isEqualByComparingTo("92");
        assertThat(existeEnLaProyeccion("DiagnosticReport", primero.informeId()))
                .as("el informe borrado vuelve")
                .isTrue();
        assertThat(existeEnLaProyeccion("Observation", resultadoDeNadie))
                .as("y lo que no era de nadie deja de estar publicado")
                .isFalse();
        assertThat(existeEnLaProyeccion("Patient", pacienteDeNadie)).isFalse();

        assertThat(reconciliador.ejecutar(null, false).divergencias())
                .as("después de reparar el laboratorio entero no queda nada que reparar, tampoco lo "
                        + "que dejaron otros tests")
                .isEmpty();
    }

    // ── El corpus ────────────────────────────────────────────────────────────────────────────────

    private record Escenario(String paciente, String resultado, String informe) {

        UUID pacienteId() {
            return UUID.fromString(CircuitoDePrueba.identidadDe(paciente));
        }

        UUID resultadoId() {
            return UUID.fromString(CircuitoDePrueba.identidadDe(resultado));
        }

        UUID informeId() {
            return UUID.fromString(CircuitoDePrueba.identidadDe(informe));
        }
    }

    private Escenario recorrerElCircuito(String laboratorio, String apellidos) {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc(), apellidos));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));
        circuito.validar(resultado);
        String informe = circuito.crear(CircuitoDePrueba.informe(paciente, laboratorio, resultado));
        return new Escenario(paciente, resultado, informe);
    }

    // ── Romper la proyección por detrás ──────────────────────────────────────────────────────────

    private void borrarDeLaProyeccion(String tipo, UUID id) {
        daos.getResourceDaoOrNull(tipo).delete(new IdType(tipo, id.toString()), comoElSistema);
    }

    private void alterarLaCifraEnLaProyeccion(UUID resultadoId, BigDecimal cifraFalsa) {
        Observation publicado = leerDeLaProyeccion(resultadoId);
        publicado.setValue(new Quantity()
                .setValue(cifraFalsa)
                .setUnit("mg/dL")
                .setSystem(CircuitoDePrueba.UCUM)
                .setCode("mg/dL"));
        daos.getResourceDao(Observation.class).update(publicado, comoElSistema);
    }

    private UUID publicarUnResultadoSinAgregado(Escenario escenario) {
        UUID inventado = UUID.randomUUID();
        Observation huerfano = new Observation();
        huerfano.setId(inventado.toString());
        huerfano.setStatus(Enumerations.ObservationStatus.FINAL);
        huerfano.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("GLU")));
        huerfano.setSubject(new Reference(escenario.paciente()));
        huerfano.setValue(new Quantity()
                .setValue(new BigDecimal("77"))
                .setUnit("mg/dL")
                .setSystem(CircuitoDePrueba.UCUM)
                .setCode("mg/dL"));

        daos.getResourceDao(Observation.class).update(huerfano, comoElSistema);
        return inventado;
    }

    /**
     * Un {@code Patient} publicado del que no responde nadie.
     *
     * <p>Es lo que deja un borrado de dominio que no llegó a la proyección, y el único de los cuatro
     * casos que el recorrido acotado no puede ver.
     */
    private UUID publicarUnPacienteSinAgregado() {
        UUID inventado = UUID.randomUUID();
        Patient huerfano = new Patient();
        huerfano.setId(inventado.toString());
        huerfano.addName(new HumanName().setFamily("Peña Muñoz").addGiven("Begoña"));
        huerfano.setGender(Enumerations.AdministrativeGender.FEMALE);

        daos.getResourceDao(Patient.class).update(huerfano, comoElSistema);
        return inventado;
    }

    // ── Mirar ────────────────────────────────────────────────────────────────────────────────────

    private Observation leerDeLaProyeccion(UUID resultadoId) {
        return daos.getResourceDao(Observation.class)
                .read(new IdType("Observation", resultadoId.toString()), comoElSistema);
    }

    private BigDecimal cifraEnLaProyeccion(UUID resultadoId) {
        return leerDeLaProyeccion(resultadoId).getValueQuantity().getValue();
    }

    private boolean existeEnLaProyeccion(String tipo, UUID id) {
        try {
            daos.getResourceDaoOrNull(tipo).read(new IdType(tipo, id.toString()), comoElSistema);
            return true;
        } catch (RuntimeException noEsta) {
            return false;
        }
    }

    private static Set<String> referenciasDe(InformeDeReconciliacion informe) {
        return informe.divergencias().stream().map(Divergencia::referencia).collect(Collectors.toSet());
    }

    /** Lo que este test ha provocado, sin lo que ya venía de antes. */
    private Set<String> loNuevoEn(InformeDeReconciliacion informe) {
        Set<String> nuevas = new LinkedHashSet<>(referenciasDe(informe));
        nuevas.removeAll(preexistentes);
        return nuevas;
    }

    private static String enSegundos(long nanos) {
        return "%.2f s".formatted(nanos / 1_000_000_000.0);
    }

    // ── De dónde sale lo que no se esperaba ──────────────────────────────────────────────────────

    /**
     * El diagnóstico que acompaña a una afirmación rota.
     *
     * <p>Existe porque un fallo de esta clase costó <strong>dos ejecuciones de CI sin diagnosticar</strong>:
     * el mensaje decía qué referencia sobraba y no decía nada más, y con un {@code UUID} suelto no se
     * puede saber si el reconciliador acaba de encontrar una divergencia de verdad en un recurso que
     * este test acababa de escribir —un fallo del sistema— o si ha recogido algo que dejó otro test
     * —un fallo de aislamiento—. Son diagnósticos opuestos y llevan a arreglos opuestos.
     *
     * <p>Así que de cada referencia inesperada se dice lo que separa las dos cosas: <strong>si es del
     * corpus</strong> de este test, <strong>qué clase</strong> de divergencia es y <strong>en qué capa
     * existe</strong>. Lo último se mide aparte, no se deduce de la clase: la clase ya dice lo que el
     * reconciliador cree —{@code AUSENTE} es dominio sí y proyección no, {@code HUERFANO} al revés,
     * {@code DISTINTO} las dos— y contrastarlo con una lectura directa es justamente lo que distingue
     * «el recurso no está» de «el recurso está y la búsqueda no lo encuentra».
     *
     * @return el diagnóstico, o la cadena vacía si no sobra nada — el caso normal, y por eso no cuesta
     */
    private String deDondeSalen(InformeDeReconciliacion informe, Set<String> inesperadas) {
        if (inesperadas.isEmpty()) {
            return "";
        }
        Map<String, Divergencia> porReferencia = informe.divergencias().stream()
                .collect(Collectors.toMap(Divergencia::referencia, Function.identity(), (una, otra) -> una));

        StringBuilder diagnostico =
                new StringBuilder("%n%d referencia(s) inesperada(s), y de dónde salen:".formatted(inesperadas.size()));
        for (String referencia : inesperadas) {
            String tipo = referencia.substring(0, referencia.indexOf('/'));
            UUID id = UUID.fromString(referencia.substring(referencia.indexOf('/') + 1));
            Divergencia divergencia = porReferencia.get(referencia);

            diagnostico.append("%n  · %-52s %-8s · %s · dominio: %s · proyección: %s"
                    .formatted(
                            referencia,
                            divergencia == null ? "?" : divergencia.clase(),
                            deQuienEs(tipo, id),
                            enElDominio(tipo, id),
                            existeEnLaProyeccion(tipo, id) ? "sí" : "no"));
        }
        return diagnostico.toString();
    }

    /** Si la referencia es de este test, y de qué parte. Es la pregunta que decide el diagnóstico. */
    private String deQuienEs(String tipo, UUID id) {
        for (int i = 0; i < corpus.size(); i++) {
            Escenario escenario = corpus.get(i);
            String cual = null;
            if (escenario.pacienteId().equals(id)) {
                cual = "el paciente";
            } else if (escenario.resultadoId().equals(id)) {
                cual = "el resultado";
            } else if (escenario.informeId().equals(id)) {
                cual = "el informe";
            }
            if (cual != null) {
                return "DEL CORPUS: %s del escenario #%d de %d".formatted(cual, i + 1, corpus.size());
            }
        }
        if (plantadas.contains(tipo + "/" + id)) {
            return "plantada a propósito por este test";
        }
        return "AJENA a este test";
    }

    /**
     * Si el dominio conoce el recurso.
     *
     * <p>El {@code Provenance} no tiene agregado detrás —es la proyección de una firma que vive dentro
     * del resultado, con identidad derivada— así que la pregunta no se le puede hacer. El informe no
     * se busca por identidad en ningún sitio del sistema, y no se le va a añadir un método al
     * repositorio para un diagnóstico: se barre, que solo pasa cuando algo ya ha fallado.
     */
    private String enElDominio(String tipo, UUID id) {
        return switch (tipo) {
            case "Patient" -> siONo(pacientes.buscarPorId(id).isPresent());
            case "ServiceRequest" -> siONo(peticiones.buscarPorId(id).isPresent());
            case "Specimen" -> siONo(especimenes.buscarPorId(id).isPresent());
            case "Observation" -> siONo(resultados.buscarPorId(id).isPresent());
            case "DiagnosticReport" -> siONo(algunPacienteLoTieneEmitido(id));
            case "Provenance" -> "no aplica (se deriva de la firma del resultado)";
            default -> "?";
        };
    }

    private boolean algunPacienteLoTieneEmitido(UUID informeId) {
        return pacientes.todasLasIdentidades().stream()
                .flatMap(paciente -> informes.buscarDePaciente(paciente).stream())
                .map(Informe::id)
                .anyMatch(informeId::equals);
    }

    private static String siONo(boolean loEs) {
        return loEs ? "sí" : "no";
    }

    private static Set<String> loQueSobraDe(Set<String> encontradas, Set<String> esperadas) {
        Set<String> sobrantes = new LinkedHashSet<>(encontradas);
        sobrantes.removeAll(esperadas);
        return sobrantes;
    }
}
