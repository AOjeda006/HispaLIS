package es.hispalis.backend.fhir.terminologia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.dominio.resultado.NoSeSabeSiEsCritico;
import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import es.hispalis.backend.infraestructura.terminologia.TerminologiaDelServidor;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.DecimalType;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Enumerations.ConceptMapRelationship;
import org.hl7.fhir.r5.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.StringType;
import org.hl7.fhir.r5.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * El nombre de la prueba sale del servidor de terminología y llega hasta el recurso publicado.
 *
 * <p>El servidor contra el que se resuelve es <strong>el HAPI de este mismo proceso</strong>, hablado
 * por HTTP con las operaciones estándar. No es un doble: es la misma implementación de {@code $lookup},
 * {@code $validate-code} y {@code $translate} que sirve el contenedor de terminología del
 * {@code compose}, y el cliente no sabe que está al otro lado del mismo puerto. Lo que se prueba aquí
 * es que <em>la proyección lleva el nombre en español y el LOINC</em>, y que una prueba que el
 * catálogo no contiene no entra.
 *
 * <p>El catálogo que se sube es un <strong>recorte</strong> de tres pruebas con la forma exacta del
 * que publica la guía —incluido un mapeo que <em>no</em> es equivalente y los límites críticos del
 * potasio con su procedencia—. No es una lista paralela: nada del código de aplicación la conoce, y
 * en el {@code compose} lo que se carga es la guía entera.
 */
// La terminología de esta clase es la de abajo, no el doble compartido de `TestDeIntegracion`:
// dos beans `@Primary` del mismo tipo no conviven. Ver `TerminologiaDeLosTests`.
@TestPropertySource(properties = "hispalis.test.terminologia=propia")
@Import(TerminologiaEnLaProyeccionTest.ContraElHapiDeEsteProceso.class)
class TerminologiaEnLaProyeccionTest extends TestDeIntegracion {

    private static final String CONJUNTO = "https://aojeda006.github.io/HispaLIS/fhir/ValueSet/pruebas-del-catalogo";
    private static final String MAPA = "https://aojeda006.github.io/HispaLIS/fhir/ConceptMap/catalogo-a-loinc";
    private static final String LOINC = "http://loinc.org";
    private static final String UCUM = "http://unitsofmeasure.org";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    @Autowired
    private Terminologia terminologia;

    private CircuitoDePrueba circuito;

    @BeforeEach
    void cargarLaTerminologia() {
        circuito = new CircuitoDePrueba(rest, contexto);
        subir("CodeSystem/catalogo-pruebas", catalogo());
        subir("ValueSet/pruebas-del-catalogo", conjuntoDePruebas());
        subir("ConceptMap/catalogo-a-loinc", mapaALoinc());
    }

    @Test
    @DisplayName("la petición publicada lleva el nombre en español y el LOINC equivalente")
    void laPeticionSaleConNombreYLoinc() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());

        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));

        CodeableConcept prueba =
                circuito.leer(linea, ServiceRequest.class).getCode().getConcept();
        assertThat(prueba.getText()).isEqualTo("Glucosa");
        assertThat(prueba.getCoding()).hasSize(2);
        assertThat(prueba.getCoding().get(0).getSystem()).isEqualTo(CatalogoDePruebas.SYSTEM);
        assertThat(prueba.getCoding().get(0).getDisplay()).isEqualTo("Glucosa");
        assertThat(prueba.getCoding().get(1).getSystem()).isEqualTo(LOINC);
        assertThat(prueba.getCoding().get(1).getCode()).isEqualTo("2345-7");
    }

    @Test
    @DisplayName("el resultado también, porque quien lo recibe fuera solo entiende el LOINC")
    void elResultadoTambien() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));

        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));

        CodeableConcept prueba = circuito.leer(resultado, Observation.class).getCode();
        assertThat(prueba.getText()).isEqualTo("Glucosa");
        assertThat(prueba.getCoding()).extracting(Coding::getSystem).containsExactly(CatalogoDePruebas.SYSTEM, LOINC);
    }

    @Test
    @DisplayName("una prueba que el catálogo no oferta no entra en el laboratorio")
    void laPruebaQueNoSeOfertaNoEntra() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());

        ServiceRequest linea = CircuitoDePrueba.linea(paciente, laboratorio);
        linea.setCode(new CodeableReference(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("ORO_COLOIDAL"))));

        ResponseEntity<String> respuesta = circuito.enviar(linea);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody()).contains("OperationOutcome").contains("ORO_COLOIDAL");
    }

    @Test
    @DisplayName("un mapeo que no es equivalente no se publica como si lo fuera")
    void loQueNoEsEquivalenteNoSale() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());

        ServiceRequest hematocrito = CircuitoDePrueba.linea(paciente, laboratorio);
        hematocrito.setCode(new CodeableReference(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("HTO"))));

        CodeableConcept prueba = circuito.leer(circuito.crear(hematocrito), ServiceRequest.class)
                .getCode()
                .getConcept();

        assertThat(prueba.getText()).isEqualTo("Hematocrito");
        assertThat(prueba.getCoding()).hasSize(1);
        assertThat(prueba.getCoding().get(0).getSystem()).isEqualTo(CatalogoDePruebas.SYSTEM);
    }

    @Test
    @DisplayName("un potasio de 6,2 NO es crítico y uno de 7,5 sí")
    void elCriterioDelPotasio() {
        // El de 6,2 está fuera de rango y no obliga a nada: es el caso que separa «anormal» de
        // «urgente», y el que se equivoca cualquier implementación que confunda las dos cosas.
        assertThat(terminologia.esCritico("K", new BigDecimal("6.2"), "mmol/L")).isFalse();
        assertThat(terminologia.esCritico("K", new BigDecimal("7.5"), "mmol/L")).isTrue();
        // Y por lo bajo, que es la mitad que se olvida: una hipopotasemia grave también se avisa.
        assertThat(terminologia.esCritico("K", new BigDecimal("2.5"), "mmol/L")).isTrue();
        assertThat(terminologia.esCritico("K", new BigDecimal("4.1"), "mmol/L")).isFalse();
        // El límite exacto avisa. Un catálogo que dice «a partir de 6,3» no dice «a partir de 6,31».
        assertThat(terminologia.esCritico("K", new BigDecimal("6.3"), "mmol/L")).isTrue();
    }

    @Test
    @DisplayName("el umbral llega del servidor con su procedencia dentro, no de una lista de aquí")
    void elUmbralLlegaConSuProcedencia() {
        Optional<UmbralCritico> umbral = terminologia.umbralDe("K");

        assertThat(umbral).isPresent();
        assertThat(umbral.get().limiteBajo()).contains(new BigDecimal("2.8"));
        assertThat(umbral.get().limiteAlto()).contains(new BigDecimal("6.3"));
        assertThat(umbral.get().unidadUcum()).isEqualTo("mmol/L");
        // Lo que hace auditable la cifra: viaja con ella, en el mismo recurso que la publica.
        assertThat(umbral.get().procedencia()).contains("SEQC 2010").contains("Rev Lab Clin. 2010;3(4):177-182");
    }

    @Test
    @DisplayName("una prueba sin umbral publicado no tiene umbral; no es lo mismo que no saberlo")
    void laPruebaSinUmbralNoTieneUmbral() {
        assertThat(terminologia.umbralDe("GLU")).isEmpty();
        assertThat(terminologia.esCritico("GLU", new BigDecimal("600"), "mg/dL"))
                .isFalse();
    }

    @Test
    @DisplayName("la regla refleja sale del catálogo, con su frase ya redactada")
    void laReglaReflejaSaleDelCatalogo() {
        assertThat(terminologia.reflejaDe("TSH")).hasValueSatisfying(regla -> {
            assertThat(regla.codigoReflejo()).isEqualTo("T4L");
            assertThat(regla.motivo()).startsWith("Derivada de un TSH alterado");
        });
    }

    @Test
    @DisplayName("una prueba que no refleja nada no refleja nada, y eso no es un fallo")
    void laPruebaQueNoReflejaNada() {
        assertThat(terminologia.reflejaDe("GLU")).isEmpty();
        assertThat(terminologia.reflejaDe("T4L")).isEmpty();
    }

    @Test
    @DisplayName("un potasio en otra unidad no se declara «no crítico»: se rechaza la comparación")
    void enOtraUnidadNoSeCompara() {
        assertThatThrownBy(() -> terminologia.esCritico("K", new BigDecimal("7.5"), "mg/dL"))
                .isInstanceOf(NoSeSabeSiEsCritico.class)
                .hasMessageContaining("mmol/L")
                .hasMessageContaining("mg/dL");
    }

    private void subir(String referencia, org.hl7.fhir.r5.model.Resource recurso) {
        ResponseEntity<String> respuesta =
                rest.exchange("/fhir/" + referencia, HttpMethod.PUT, circuito.peticionCon(recurso), String.class);
        assertThat(respuesta.getStatusCode())
                .as("no se pudo cargar %s: %s", referencia, respuesta.getBody())
                .isIn(HttpStatus.CREATED, HttpStatus.OK);
    }

    private static CodeSystem catalogo() {
        CodeSystem catalogo = new CodeSystem();
        catalogo.setId("catalogo-pruebas");
        catalogo.setUrl(CatalogoDePruebas.SYSTEM);
        catalogo.setStatus(PublicationStatus.DRAFT);
        catalogo.setContent(Enumerations.CodeSystemContentMode.COMPLETE);
        catalogo.setCaseSensitive(true);
        catalogo.setValueSet(CONJUNTO);
        catalogo.addProperty().setCode("unidad-ucum").setType(CodeSystem.PropertyType.CODING);
        catalogo.addProperty().setCode("limite-critico-bajo").setType(CodeSystem.PropertyType.DECIMAL);
        catalogo.addProperty().setCode("limite-critico-alto").setType(CodeSystem.PropertyType.DECIMAL);
        catalogo.addProperty().setCode("procedencia-del-valor-critico").setType(CodeSystem.PropertyType.STRING);
        catalogo.addProperty().setCode("prueba-refleja").setType(CodeSystem.PropertyType.CODE);
        catalogo.addProperty().setCode("motivo-de-la-refleja").setType(CodeSystem.PropertyType.STRING);
        catalogo.addConcept().setCode("GLU").setDisplay("Glucosa");
        catalogo.addConcept().setCode("HTO").setDisplay("Hematocrito");

        // El par de la refleja, con la forma que tiene en la guía: la TSH dice a qué prueba refleja y
        // con qué palabras se cuenta; la T4 libre no refleja a nada, que también hay que comprobarlo.
        CodeSystem.ConceptDefinitionComponent tsh =
                catalogo.addConcept().setCode("TSH").setDisplay("TSH (tirotropina)");
        tsh.addProperty().setCode("unidad-ucum").setValue(new Coding(UCUM, "u[IU]/mL", null));
        tsh.addProperty().setCode("prueba-refleja").setValue(new CodeType("T4L"));
        tsh.addProperty()
                .setCode("motivo-de-la-refleja")
                .setValue(new StringType("Derivada de un TSH alterado: el protocolo de función tiroidea del "
                        + "laboratorio añade la T4 libre cuando la TSH cae fuera de su rango de referencia."));

        CodeSystem.ConceptDefinitionComponent t4l =
                catalogo.addConcept().setCode("T4L").setDisplay("T4 libre");
        t4l.addProperty().setCode("unidad-ucum").setValue(new Coding(UCUM, "ng/dL", null));

        // El potasio, con la forma exacta que tiene en la guía: la unidad, los dos límites y la
        // procedencia. Las cifras son las publicadas — SEQC 2010, tabla 6, consulta externa—, y aquí
        // están porque el test que importa es que 6,2 no dispare y 7,5 sí.
        CodeSystem.ConceptDefinitionComponent potasio =
                catalogo.addConcept().setCode("K").setDisplay("Potasio");
        potasio.addProperty().setCode("unidad-ucum").setValue(new Coding(UCUM, "mmol/L", null));
        potasio.addProperty().setCode("limite-critico-bajo").setValue(new DecimalType("2.8"));
        potasio.addProperty().setCode("limite-critico-alto").setValue(new DecimalType("6.3"));
        potasio.addProperty()
                .setCode("procedencia-del-valor-critico")
                .setValue(
                        new StringType(
                                "SEQC 2010, tabla 6: mediana declarada para consulta externa (Rev Lab Clin. 2010;3(4):177-182)."));
        return catalogo;
    }

    private static ValueSet conjuntoDePruebas() {
        ValueSet conjunto = new ValueSet();
        conjunto.setId("pruebas-del-catalogo");
        conjunto.setUrl(CONJUNTO);
        conjunto.setStatus(PublicationStatus.DRAFT);
        conjunto.getCompose().addInclude().setSystem(CatalogoDePruebas.SYSTEM);
        return conjunto;
    }

    private static ConceptMap mapaALoinc() {
        ConceptMap mapa = new ConceptMap();
        mapa.setId("catalogo-a-loinc");
        mapa.setUrl(MAPA);
        mapa.setStatus(PublicationStatus.DRAFT);
        mapa.setSourceScope(new org.hl7.fhir.r5.model.CanonicalType(CONJUNTO));
        mapa.setTargetScope(new org.hl7.fhir.r5.model.UriType("http://loinc.org/vs"));

        ConceptMap.ConceptMapGroupComponent grupo =
                mapa.addGroup().setSource(CatalogoDePruebas.SYSTEM).setTarget(LOINC);
        grupo.addElement()
                .setCode("GLU")
                .addTarget()
                .setCode("2345-7")
                .setDisplay("Glucose [Mass/volume] in Serum or Plasma")
                .setRelationship(ConceptMapRelationship.EQUIVALENT);
        // El hematocrito del laboratorio no dice el método; el LOINC sí. No son lo mismo, y el mapa
        // lo declara. Está aquí para que el test pueda comprobar que esa diferencia se respeta.
        grupo.addElement()
                .setCode("HTO")
                .addTarget()
                .setCode("4544-3")
                .setDisplay("Hematocrit [Volume Fraction] of Blood by Automated count")
                .setRelationship(ConceptMapRelationship.SOURCEISBROADERTHANTARGET);
        return mapa;
    }

    /**
     * Apunta la terminología al servidor FHIR de este mismo proceso.
     *
     * <p>El cliente se construye <strong>al primer uso</strong>, no al crear el bean: el puerto lo
     * asigna Spring Boot cuando arranca el servidor web, y eso pasa después de que los beans estén
     * hechos. Es el único motivo de que aquí haya un envoltorio perezoso.
     */
    @TestConfiguration
    static class ContraElHapiDeEsteProceso {

        /**
         * Le presta al servidor de este proceso las operaciones de {@code ValueSet}.
         *
         * <p>El laboratorio <strong>no las publica</strong>, y eso no es un descuido: D14 dice que la
         * terminología es un servicio aparte, y HAPI no registra {@code ValueSet/$validate-code} ni
         * {@code $expand} si nadie da de alta su proveedor. Aquí se dan de alta porque el servidor de
         * este proceso hace de servidor de terminología para el test, no porque el laboratorio deba
         * responderlas.
         */
        @Bean
        org.springframework.beans.factory.SmartInitializingSingleton prestarLasOperacionesDeValueSet(
                ca.uhn.fhir.rest.server.RestfulServer servidor,
                ca.uhn.fhir.jpa.provider.ValueSetOperationProvider proveedor) {
            return () -> servidor.registerProvider(proveedor);
        }

        @Bean
        @Primary
        es.hispalis.backend.fhir.terminologia.Terminologia terminologiaDeEsteProceso(
                FhirContext contexto, Environment entorno) {
            return new AlPrimerUso(() -> {
                contexto.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
                String base = "http://localhost:" + entorno.getProperty("local.server.port") + "/fhir";
                return new TerminologiaDelServidor(contexto.newRestfulGenericClient(base));
            });
        }
    }

    private record AlPrimerUso(Supplier<TerminologiaDelServidor> comoLlegar)
            implements es.hispalis.backend.fhir.terminologia.Terminologia {

        private static volatile TerminologiaDelServidor yaConstruida;

        @Override
        public CodeableConcept pruebaDelCatalogo(String codigoLocal) {
            return construir().pruebaDelCatalogo(codigoLocal);
        }

        @Override
        public void exigirQueLaPruebaExiste(String codigoLocal) {
            construir().exigirQueLaPruebaExiste(codigoLocal);
        }

        @Override
        public Optional<UmbralCritico> umbralDe(String codigoDePrueba) {
            return construir().umbralDe(codigoDePrueba);
        }

        @Override
        public Optional<es.hispalis.backend.dominio.resultado.ReglaRefleja> reflejaDe(String codigoDePrueba) {
            return construir().reflejaDe(codigoDePrueba);
        }

        private TerminologiaDelServidor construir() {
            TerminologiaDelServidor construida = yaConstruida;
            if (construida == null) {
                construida = comoLlegar.get();
                yaConstruida = construida;
            }
            return construida;
        }
    }
}
