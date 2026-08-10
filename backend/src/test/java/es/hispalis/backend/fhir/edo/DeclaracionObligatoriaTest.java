package es.hispalis.backend.fhir.edo;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.dominio.edo.ModalidadDeDeclaracion;
import es.hispalis.backend.dominio.edo.ReglaDeDeclaracion;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import es.hispalis.backend.dominio.resultado.ReglaRefleja;
import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import es.hispalis.backend.fhir.ResultadosCualitativos;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * La detección de una enfermedad de declaración obligatoria, al validarse el resultado.
 *
 * <p>Es la regla de negocio con motivo legal del proyecto: todos los centros sanitarios de Andalucía,
 * <strong>públicos y privados</strong>, forman parte del Sistema de Vigilancia Epidemiológica
 * (Decreto 66/1996). Que este laboratorio sea privado no le exime.
 *
 * <p>Lo que se comprueba es que la decisión sale de <strong>dos códigos</strong> —el de la prueba y
 * el del valor— y de nada más. La prueba de que no mira al paciente no es un test que lo afirme: es
 * que los dos escenarios usan la MISMA prueba sobre pacientes distintos y se separan solo por el
 * código del resultado.
 */
// La terminología de esta clase es la de abajo, no el doble compartido de `TestDeIntegracion`:
// dos beans `@Primary` del mismo tipo no conviven. Ver `TerminologiaDeLosTests`.
@TestPropertySource(properties = "hispalis.test.terminologia=propia")
@Import(DeclaracionObligatoriaTest.ConElCatalogoEdo.class)
class DeclaracionObligatoriaTest extends TestDeIntegracion {

    private static final String LEGIONELLA = "LEGIOAG";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private CircuitoDePrueba circuito;

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
    }

    @Test
    @DisplayName("un Legionella POSITIVO validado deja apuntada la obligación de declarar")
    void unLegionellaPositivoDispara() {
        String resultado = unaLegionella("POS");

        circuito.validar(resultado);

        assertThat(hechosDe(resultado))
                .as("la obligación se apunta en la misma transacción que la validación: nace y queda registrada")
                .contains(TipoDeHecho.RESULTADO_DECLARABLE.name());
    }

    @Test
    @DisplayName("y uno NEGATIVO no dispara nada: la misma prueba, el mismo perfil, otro código")
    void unLegionellaNegativoNoDispara() {
        String resultado = unaLegionella("NEG");

        circuito.validar(resultado);

        assertThat(hechosDe(resultado))
                .as("un negativo de una prueba EDO no es información para Salud Pública")
                .contains(TipoDeHecho.RESULTADO_VALIDADO.name())
                .doesNotContain(TipoDeHecho.RESULTADO_DECLARABLE.name());
    }

    /**
     * Un indeterminado tampoco. El criterio es {@code POS}, no «todo lo que no sea negativo».
     *
     * <p>Una serología en zona gris no es un caso confirmado, y declararla metería en el sistema de
     * vigilancia epidemiológica casos que hay que retirar después — que no es un exceso de celo
     * inofensivo: cada retirada cuesta una investigación.
     */
    @Test
    @DisplayName("un INDETERMINADO tampoco declara: el criterio es un código concreto, no «no negativo»")
    void unIndeterminadoNoDispara() {
        String resultado = unaLegionella("IND");

        circuito.validar(resultado);

        assertThat(hechosDe(resultado)).doesNotContain(TipoDeHecho.RESULTADO_DECLARABLE.name());
    }

    /**
     * Y una prueba que no está en el catálogo EDO no declara aunque salga positiva.
     *
     * <p>Control negativo del otro lado: sin él, «declarar todo lo que salga POS» pasaría por bueno.
     */
    @Test
    @DisplayName("un positivo de una prueba que no es EDO no declara nada")
    void unPositivoDeOtraPruebaNoDispara() {
        String resultado = unResultadoCualitativo("HBSAG", "POS");

        circuito.validar(resultado);

        assertThat(hechosDe(resultado)).doesNotContain(TipoDeHecho.RESULTADO_DECLARABLE.name());
    }

    /** Antes de la validación no hay nada que declarar: se declara lo definitivo, no lo que midió la máquina. */
    @Test
    @DisplayName("un positivo sin validar no declara: primero responde alguien de él")
    void sinValidarNoSeDeclara() {
        String resultado = unaLegionella("POS");

        assertThat(hechosDe(resultado))
                .contains(TipoDeHecho.RESULTADO_INFORMADO.name())
                .doesNotContain(TipoDeHecho.RESULTADO_DECLARABLE.name());
    }

    /**
     * El hecho que se apunta no dice de qué enfermedad se trata, y eso es el invariante 6.
     *
     * <p>{@code {pacienteId, enfermedad: LEGIONELOSIS}} en un tópico replicado dice «esta persona
     * tiene legionelosis», que es historia clínica viajando por el bus. Lo que va es la referencia; la
     * enfermedad se lee de la API, donde se aplica el consentimiento del paciente.
     */
    @Test
    @DisplayName("el hecho lleva la referencia y NO la enfermedad")
    void elHechoNoLlevaLaEnfermedad() {
        String resultado = unaLegionella("POS");
        circuito.validar(resultado);

        String carga = jdbc.queryForObject(
                "SELECT carga::text FROM outbox.hecho WHERE tipo = :tipo AND carga::text LIKE :ref",
                new MapSqlParameterSource()
                        .addValue("tipo", TipoDeHecho.RESULTADO_DECLARABLE.name())
                        .addValue("ref", "%" + CircuitoDePrueba.identidadDe(resultado) + "%"),
                String.class);

        assertThat(carga)
                .contains("observationRef")
                .doesNotContain("LEGIONELOSIS")
                .doesNotContainIgnoringCase("legionel");
    }

    /** Y el resultado publicado sale codificado y con su nombre: la web y la app leen `text`. */
    @Test
    @DisplayName("el valor cualitativo se publica con código y con nombre en español")
    void elValorSalePublicadoConNombre() {
        String resultado = unaLegionella("POS");

        CodeableConcept valor = circuito.leer(resultado, Observation.class).getValueCodeableConcept();

        assertThat(valor.getCodingFirstRep().getSystem()).isEqualTo(ResultadosCualitativos.SYSTEM);
        assertThat(valor.getCodingFirstRep().getCode()).isEqualTo("POS");
        assertThat(valor.getText())
                .as("un `POS` a secas deja a la web y a la app enseñando un hueco")
                .isEqualTo("Positivo");
    }

    // ─── Andamiaje ──────────────────────────────────────────────────────────

    private String unaLegionella(String codigoDelValor) {
        return unResultadoCualitativo(LEGIONELLA, codigoDelValor);
    }

    private String unResultadoCualitativo(String prueba, String codigoDelValor) {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));

        Observation resultado = CircuitoDePrueba.resultado(paciente, muestra, null, laboratorio);
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(prueba)));
        resultado.setValue(new CodeableConcept()
                .addCoding(new Coding().setSystem(ResultadosCualitativos.SYSTEM).setCode(codigoDelValor))
                // Se manda también el texto para comprobar que NO gana al código: es la forma en la
                // que un cliente real lo mandaría, y era justo lo que hacía perder el código antes.
                .setText(codigoDelValor.equals("POS") ? "Positivo" : "Otro"));
        return circuito.crear(resultado);
    }

    private List<String> hechosDe(String resultado) {
        return jdbc.queryForList(
                "SELECT tipo FROM outbox.hecho WHERE carga::text LIKE :ref",
                new MapSqlParameterSource("ref", "%" + CircuitoDePrueba.identidadDe(resultado) + "%"),
                String.class);
    }

    /**
     * Una terminología que contesta lo que contestaría la guía para el antígeno de Legionella.
     *
     * <p>Trae también los nombres en español de los valores cualitativos, porque sin ellos el
     * resultado publicado no se puede leer — y esa es la mitad que se comprueba en el último test.
     */
    @TestConfiguration
    static class ConElCatalogoEdo {

        private static final Map<String, String> NOMBRES =
                Map.of("POS", "Positivo", "NEG", "Negativo", "IND", "Indeterminado");

        @Bean
        @Primary
        Terminologia terminologiaConElCatalogoEdo() {
            return new Terminologia() {

                @Override
                public CodeableConcept pruebaDelCatalogo(String codigoLocal) {
                    return new CodeableConcept()
                            .addCoding(new Coding()
                                    .setSystem(CatalogoDePruebas.SYSTEM)
                                    .setCode(codigoLocal));
                }

                @Override
                public CodeableConcept valorCualitativo(String codigoLocal) {
                    CodeableConcept concepto = new CodeableConcept()
                            .addCoding(new Coding()
                                    .setSystem(ResultadosCualitativos.SYSTEM)
                                    .setCode(codigoLocal)
                                    .setDisplay(NOMBRES.get(codigoLocal)));
                    return concepto.setText(NOMBRES.get(codigoLocal));
                }

                @Override
                public void exigirQueLaPruebaExiste(String codigoLocal) {
                    // Sin autoridad a la que preguntar, rechazar sería inventarse la respuesta.
                }

                @Override
                public Optional<UmbralCritico> umbralDe(String codigoDePrueba) {
                    return Optional.empty();
                }

                @Override
                public Optional<ReglaRefleja> reflejaDe(String codigoDePrueba) {
                    return Optional.empty();
                }

                @Override
                public Optional<ReglaDeDeclaracion> declaracionDe(String codigoDePrueba) {
                    return LEGIONELLA.equals(codigoDePrueba)
                            ? Optional.of(new ReglaDeDeclaracion(
                                    LEGIONELLA,
                                    "LEGIONELOSIS",
                                    "Legionelosis",
                                    "POS",
                                    ModalidadDeDeclaracion.URGENTE,
                                    Duration.ofHours(24)))
                            : Optional.empty();
                }
            };
        }
    }
}
