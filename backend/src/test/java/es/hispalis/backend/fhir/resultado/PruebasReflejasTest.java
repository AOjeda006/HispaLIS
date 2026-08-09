package es.hispalis.backend.fhir.resultado;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.dominio.resultado.ReglaRefleja;
import es.hispalis.backend.dominio.resultado.UmbralCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations.RequestIntent;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Observation.TriggeredBytype;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * La prueba refleja: el laboratorio añade una determinación que nadie pidió, y dice por qué.
 *
 * <p>⚠️ {@code Observation.triggeredBy} <strong>no existe en R4</strong>. Antes de R5, enlazar una
 * T4 libre con la TSH que la provocó había que inventarlo con una extensión propia, y por eso en la
 * práctica no se hacía: el informe enseñaba dos determinaciones y el clínico deducía la relación.
 *
 * <p>Lo que se comprueba aquí es <strong>quién decide qué</strong>. El catálogo dice a qué prueba
 * refleja la TSH y con qué palabras se cuenta; el dominio dice cuándo, mirando el rango de
 * referencia. Y lo que el cliente NO puede decidir es que algo es una refleja: el protocolo es del
 * laboratorio.
 *
 * <p>La terminología es un doble <strong>solo aquí</strong>. Que la regla se lea de verdad de un
 * {@code $lookup} lo prueba {@code TerminologiaEnLaProyeccionTest} contra un HAPI real; repetir ese
 * montaje en este test escondería lo que este sí prueba, que es lo que pasa después.
 */
@Import(PruebasReflejasTest.ConLaReglaDelCatalogo.class)
class PruebasReflejasTest extends TestDeIntegracion {

    private static final String UCUM = "http://unitsofmeasure.org";

    /** La frase la redacta el catálogo, no el código. Ver `CatalogoPruebas.fsh`. */
    private static final String MOTIVO = "Derivada de un TSH alterado: el protocolo de función tiroidea del "
            + "laboratorio añade la T4 libre cuando la TSH cae fuera de su rango de referencia.";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext contexto;

    private CircuitoDePrueba circuito;
    private String paciente;
    private String laboratorio;
    private String muestra;

    @BeforeEach
    void montarElEscenario() {
        circuito = new CircuitoDePrueba(rest, contexto);
        paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
    }

    @Test
    @DisplayName("una TSH alterada hace que el laboratorio pida la T4 libre, y la línea dice que la pidió él")
    void laTshAlteradaPideLaT4Libre() {
        String linea = circuito.crear(pedir("TSH"));
        String tsh = circuito.crear(medir("TSH", "8.4", "u[IU]/mL", linea));

        List<ServiceRequest> lineas = lineasDelPaciente();

        assertThat(lineas).hasSize(2);
        ServiceRequest refleja = lineas.stream()
                .filter(l -> "T4L"
                        .equals(CatalogoDePruebas.codigoDe(l.getCode().getConcept())
                                .orElse("")))
                .findFirst()
                .orElseThrow();

        // `reflex-order`, no `order`: es la diferencia entre «esto lo pidió el clínico» y «esto lo
        // añadió el laboratorio». FHIR ya lo modela, así que no hace falta extensión ninguna.
        assertThat(refleja.getIntent()).isEqualTo(RequestIntent.REFLEXORDER);
        assertThat(refleja.getNoteFirstRep().getText()).isEqualTo(MOTIVO);
        // Hereda el volante y el peticionario de la que la disparó: R5 dice en la definición del
        // propio código `reflex` que la petición original es la que dio la autorización.
        assertThat(refleja.getRequisition().getValue())
                .isEqualTo(circuito.leer(linea, ServiceRequest.class)
                        .getRequisition()
                        .getValue());
        assertThat(tsh).isNotBlank();
    }

    @Test
    @DisplayName("el resultado de la refleja sale enlazado con `triggeredBy`, y con la frase dentro")
    void laT4LibreSaleEnlazada() {
        String linea = circuito.crear(pedir("TSH"));
        String tsh = circuito.crear(medir("TSH", "8.4", "u[IU]/mL", linea));
        String lineaRefleja = referenciaDe(lineaDe("T4L"));

        String t4l = circuito.crear(medir("T4L", "0.9", "ng/dL", lineaRefleja));

        Observation publicada = circuito.leer(t4l, Observation.class);
        assertThat(publicada.getTriggeredBy()).hasSize(1);
        assertThat(publicada.getTriggeredByFirstRep().getType()).isEqualTo(TriggeredBytype.REFLEX);
        assertThat(publicada.getTriggeredByFirstRep().getObservation().getReference())
                .isEqualTo(tsh);
        assertThat(publicada.getTriggeredByFirstRep().getReason()).isEqualTo(MOTIVO);
    }

    @Test
    @DisplayName("una TSH dentro de rango no añade nada: la refleja la dispara el valor, no la prueba")
    void laTshNormalNoPideNada() {
        String linea = circuito.crear(pedir("TSH"));

        circuito.crear(medir("TSH", "2.1", "u[IU]/mL", linea));

        assertThat(lineasDelPaciente()).hasSize(1);
    }

    @Test
    @DisplayName("reinformar la misma TSH no añade una segunda T4 libre")
    void laReflejaNoSeDuplica() {
        String linea = circuito.crear(pedir("TSH"));
        circuito.crear(medir("TSH", "8.4", "u[IU]/mL", linea));
        circuito.crear(medir("TSH", "8.4", "u[IU]/mL", linea));

        assertThat(lineasDelPaciente().stream()
                        .filter(l -> "T4L"
                                .equals(CatalogoDePruebas.codigoDe(l.getCode().getConcept())
                                        .orElse("")))
                        .toList())
                .hasSize(1);
    }

    @Test
    @DisplayName("una determinación sin volante no dispara refleja: no hay de dónde colgarla")
    void sinLineaNoHayRefleja() {
        circuito.crear(medir("TSH", "8.4", "u[IU]/mL", null));

        assertThat(lineasDelPaciente()).isEmpty();
    }

    @Test
    @DisplayName("`repeat` sí lo declara quien repite: la hemólisis del tubo solo la ve él")
    void laRepeticionLaDeclaraQuienRepite() {
        String primero = circuito.crear(medir("K", "6.9", "mmol/L", null));
        String otraMuestra = circuito.crear(CircuitoDePrueba.muestra(paciente));

        Observation repetido = medir("K", "4.3", "mmol/L", null);
        repetido.setSpecimen(new Reference(otraMuestra));
        repetido.addTriggeredBy()
                .setObservation(new Reference(primero))
                .setType(TriggeredBytype.REPEAT)
                .setReason("Repetido sobre una segunda extracción: la muestra anterior estaba hemolizada.");

        Observation publicado = circuito.leer(circuito.crear(repetido), Observation.class);

        assertThat(publicado.getTriggeredByFirstRep().getType()).isEqualTo(TriggeredBytype.REPEAT);
        assertThat(publicado.getTriggeredByFirstRep().getReason()).contains("hemolizada");
    }

    @Test
    @DisplayName("`re-run` también, y es otro caso: lo que estaba mal era el analizador")
    void laReejecucionEsOtraCosa() {
        String primero = circuito.crear(medir("NA", "149", "mmol/L", null));

        Observation reejecutado = medir("NA", "141", "mmol/L", null);
        reejecutado
                .addTriggeredBy()
                .setObservation(new Reference(primero))
                .setType(TriggeredBytype.RERUN)
                .setReason("Re-ejecutado tras recalibrar: el control de calidad del turno estaba fuera de límites.");

        Observation publicado = circuito.leer(circuito.crear(reejecutado), Observation.class);

        assertThat(publicado.getTriggeredByFirstRep().getType()).isEqualTo(TriggeredBytype.RERUN);
    }

    @Test
    @DisplayName("`reflex` NO lo puede declarar el cliente: el protocolo es del laboratorio")
    void elClienteNoDeclaraUnaRefleja() {
        String primero = circuito.crear(medir("TSH", "2.1", "u[IU]/mL", null));

        Observation inventada = medir("T4L", "0.9", "ng/dL", null);
        inventada
                .addTriggeredBy()
                .setObservation(new Reference(primero))
                .setType(TriggeredBytype.REFLEX)
                .setReason("Porque yo lo digo.");

        ResponseEntity<String> respuesta = circuito.enviar(inventada);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody()).contains("OperationOutcome").contains("catálogo");
    }

    @Test
    @DisplayName("un disparo sin motivo no se acepta: dos cifras enlazadas sin explicar no explican nada")
    void unDisparoSinMotivoNoSeAcepta() {
        String primero = circuito.crear(medir("K", "6.9", "mmol/L", null));

        Observation repetido = medir("K", "4.3", "mmol/L", null);
        repetido.addTriggeredBy().setObservation(new Reference(primero)).setType(TriggeredBytype.REPEAT);

        ResponseEntity<String> respuesta = circuito.enviar(repetido);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(respuesta.getBody()).contains("motivo");
    }

    private ServiceRequest pedir(String prueba) {
        ServiceRequest linea = CircuitoDePrueba.linea(paciente, laboratorio);
        linea.setCode(new CodeableReference(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(prueba))));
        return linea;
    }

    private Observation medir(String prueba, String valor, String unidad, String linea) {
        Observation resultado = CircuitoDePrueba.resultado(paciente, muestra, laboratorio, laboratorio);
        resultado.setBasedOn(new java.util.ArrayList<>());
        if (linea != null) {
            resultado.addBasedOn(new Reference(linea));
        }
        resultado.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(prueba)));
        resultado.setValue(new Quantity()
                .setValue(new BigDecimal(valor))
                .setUnit(unidad)
                .setSystem(UCUM)
                .setCode(unidad));
        return resultado;
    }

    private List<ServiceRequest> lineasDelPaciente() {
        String cuerpo = rest.getForObject("/fhir/ServiceRequest?subject=" + paciente, String.class);
        return contexto.newJsonParser().parseResource(Bundle.class, cuerpo).getEntry().stream()
                .map(entrada -> (ServiceRequest) entrada.getResource())
                .toList();
    }

    private ServiceRequest lineaDe(String prueba) {
        return lineasDelPaciente().stream()
                .filter(l -> prueba.equals(
                        CatalogoDePruebas.codigoDe(l.getCode().getConcept()).orElse("")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no se ha pedido ninguna " + prueba));
    }

    private static String referenciaDe(ServiceRequest linea) {
        return "ServiceRequest/" + linea.getIdElement().getIdPart();
    }

    /**
     * Una terminología que contesta lo que contestaría la guía para la TSH, y nada más.
     *
     * <p>No es un catálogo de repuesto: no valida códigos ni resuelve nombres —eso lo deja como está
     * el laboratorio sin servidor—, solo la regla refleja, que es lo que este test necesita que
     * exista para poder comprobar qué hace el dominio con ella.
     */
    @TestConfiguration
    static class ConLaReglaDelCatalogo {

        @Bean
        @Primary
        Terminologia terminologiaConRegla() {
            return new Terminologia() {

                @Override
                public CodeableConcept pruebaDelCatalogo(String codigoLocal) {
                    return new CodeableConcept()
                            .addCoding(new Coding()
                                    .setSystem(CatalogoDePruebas.SYSTEM)
                                    .setCode(codigoLocal));
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
                    return "TSH".equals(codigoDePrueba)
                            ? Optional.of(new ReglaRefleja("TSH", "T4L", MOTIVO))
                            : Optional.empty();
                }
            };
        }
    }
}
