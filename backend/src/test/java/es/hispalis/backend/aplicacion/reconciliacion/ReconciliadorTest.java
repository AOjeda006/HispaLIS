package es.hispalis.backend.aplicacion.reconciliacion;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.aplicacion.reconciliacion.Divergencia.Clase;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import java.math.BigDecimal;
import java.util.UUID;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * El reconciliador: la vía de recuperación oficial de §15.
 *
 * <p>La proyección se corrompe <strong>a propósito</strong> y de las tres formas en que puede
 * corromperse: borrando un recurso, alterando otro y publicando uno que no tiene agregado detrás. Un
 * test que solo borrara pasaría con un reconciliador que reescribe el dominio encima y nunca mira si
 * sobra algo — y eso es justo la mitad que faltaba en el incidente del {@code Bundle transaction}.
 *
 * <p>Se escribe directamente por las DAO de HAPI, saltándose la API. No es hacer trampa: es
 * <em>reproducir el fallo</em>. La divergencia que este código repara no la produce un cliente
 * —{@code EscrituraSoloPorElNucleo} le cierra esa puerta—, la produce un bug del propio laboratorio
 * escribiendo mal su proyección, y eso es exactamente lo que se simula aquí.
 */
class ReconciliadorTest extends TestDeIntegracion {

    private final FhirContext contexto = FhirContext.forR5();
    private final SystemRequestDetails comoElSistema = new SystemRequestDetails();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Reconciliador reconciliador;

    @Autowired
    private DaoRegistry daos;

    private CircuitoDePrueba circuito;

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
    }

    @Test
    void sobre_un_paciente_intacto_no_encuentra_nada() {
        Escenario escenario = recorrerElCircuito();

        InformeDeReconciliacion informe = reconciliador.ejecutar(escenario.pacienteId(), false);

        assertThat(informe.todoCuadra())
                .as(
                        "la proyección se escribe en la transacción del dominio: recién escrita tiene que cuadrar. %s",
                        informe.divergencias())
                .isTrue();
    }

    /**
     * Se borra el informe y no el resultado porque HAPI se niega a borrar un recurso al que otro
     * apunta —el {@code DiagnosticReport} referencia al {@code Observation}, y el {@code Provenance}
     * también—, y esa negativa es un comportamiento que conviene conservar. El informe es una hoja
     * del grafo: nadie lo referencia, así que es el sitio por donde la proyección sí se puede
     * agujerear.
     */
    @Test
    void lo_que_falta_en_la_proyeccion_sale_como_ausente() {
        Escenario escenario = recorrerElCircuito();
        borrarDeLaProyeccion("DiagnosticReport", escenario.informeId());

        InformeDeReconciliacion informe = reconciliador.ejecutar(escenario.pacienteId(), false);

        assertThat(informe.de(Clase.AUSENTE))
                .extracting(Divergencia::referencia)
                .containsExactly("DiagnosticReport/" + escenario.informeId());
    }

    @Test
    void lo_que_dice_algo_distinto_del_dominio_sale_como_distinto() {
        Escenario escenario = recorrerElCircuito();
        alterarLaCifraEnLaProyeccion(escenario.resultadoId(), new BigDecimal("999"));

        InformeDeReconciliacion informe = reconciliador.ejecutar(escenario.pacienteId(), false);

        assertThat(informe.de(Clase.DISTINTO))
                .extracting(Divergencia::referencia)
                .containsExactly("Observation/" + escenario.resultadoId());
    }

    /** La mitad que un reconciliador ingenuo no detecta: lo que sobra. */
    @Test
    void lo_que_esta_publicado_sin_agregado_detras_sale_como_huerfano() {
        Escenario escenario = recorrerElCircuito();
        UUID inventado = publicarUnResultadoSinAgregado(escenario);

        InformeDeReconciliacion informe = reconciliador.ejecutar(escenario.pacienteId(), false);

        assertThat(informe.de(Clase.HUERFANO))
                .extracting(Divergencia::referencia)
                .containsExactly("Observation/" + inventado);
    }

    /** Decir qué va a cambiar antes de cambiarlo: revisar no escribe. */
    @Test
    void revisar_no_toca_la_proyeccion() {
        Escenario escenario = recorrerElCircuito();
        alterarLaCifraEnLaProyeccion(escenario.resultadoId(), new BigDecimal("999"));

        InformeDeReconciliacion revision = reconciliador.ejecutar(escenario.pacienteId(), false);

        assertThat(revision.aplicado()).isFalse();
        assertThat(revision.divergencias()).isNotEmpty();
        assertThat(cifraEnLaProyeccion(escenario.resultadoId()))
                .as("la revisión avisó, pero no arregló nada")
                .isEqualByComparingTo("999");
    }

    /** Las tres corrupciones a la vez, reparadas de una pasada. */
    @Test
    void aplicar_deja_la_proyeccion_como_el_dominio() {
        Escenario escenario = recorrerElCircuito();
        borrarDeLaProyeccion("DiagnosticReport", escenario.informeId());
        alterarLaCifraEnLaProyeccion(escenario.resultadoId(), new BigDecimal("999"));
        UUID sobrante = publicarUnResultadoSinAgregado(escenario);

        InformeDeReconciliacion reparacion = reconciliador.ejecutar(escenario.pacienteId(), true);

        assertThat(reparacion.aplicado()).isTrue();
        assertThat(reparacion.divergencias()).hasSize(3);
        assertThat(cifraEnLaProyeccion(escenario.resultadoId()))
                .as("la cifra vuelve a ser la del dominio")
                .isEqualByComparingTo("92");
        assertThat(existeEnLaProyeccion("DiagnosticReport", escenario.informeId()))
                .as("el informe borrado vuelve")
                .isTrue();
        assertThat(existeEnLaProyeccion("Observation", sobrante))
                .as("y el resultado que no era de nadie deja de estar publicado")
                .isFalse();

        assertThat(reconciliador.ejecutar(escenario.pacienteId(), false).todoCuadra())
                .as("después de reparar, el dominio y la proyección dicen lo mismo")
                .isTrue();
    }

    @Test
    void reconciliar_dos_veces_no_cambia_nada_la_segunda() {
        Escenario escenario = recorrerElCircuito();
        borrarDeLaProyeccion("DiagnosticReport", escenario.informeId());

        reconciliador.ejecutar(escenario.pacienteId(), true);
        InformeDeReconciliacion segunda = reconciliador.ejecutar(escenario.pacienteId(), true);

        assertThat(segunda.todoCuadra())
                .as("una vía de recuperación que no es idempotente no se puede reintentar. %s", segunda.divergencias())
                .isTrue();
    }

    /**
     * El recorrido acotado: reconciliar a una persona no mira ni toca a las demás.
     *
     * <p>Sin esto, recuperar de un incidente de un paciente exigiría recorrer el laboratorio entero,
     * que en un sistema con historia es lo que hace que la vía oficial no se use nunca.
     */
    @Test
    void se_puede_ejecutar_sobre_un_solo_paciente() {
        Escenario unaPersona = recorrerElCircuito();
        Escenario otraPersona = recorrerElCircuito();
        alterarLaCifraEnLaProyeccion(otraPersona.resultadoId(), new BigDecimal("999"));

        InformeDeReconciliacion informe = reconciliador.ejecutar(unaPersona.pacienteId(), true);

        assertThat(informe.todoCuadra()).isTrue();
        assertThat(cifraEnLaProyeccion(otraPersona.resultadoId()))
                .as("lo de la otra persona no se mira y, sobre todo, no se toca")
                .isEqualByComparingTo("999");
    }

    /** Y está publicada donde se puede usar: no es un guion suelto (§15). */
    @Test
    void la_operacion_responde_por_la_api() {
        Escenario escenario = recorrerElCircuito();
        alterarLaCifraEnLaProyeccion(escenario.resultadoId(), new BigDecimal("999"));

        Parameters peticion = new Parameters();
        peticion.addParameter().setName("paciente").setValue(new Reference("Patient/" + escenario.pacienteId()));

        ResponseEntity<String> respuesta =
                rest.exchange("/fhir/$reconciliar", HttpMethod.POST, circuito.peticionCon(peticion), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        Parameters informe = contexto.newJsonParser().parseResource(Parameters.class, respuesta.getBody());
        assertThat(informe.getParameterBool("aplicado"))
                .as("sin pedirlo explícitamente, la operación solo mira")
                .isFalse();
        assertThat(informe.getParameterValue("divergencias").primitiveValue()).isEqualTo("1");
    }

    // ── El escenario ─────────────────────────────────────────────────────────────────────────────

    private record Escenario(String paciente, String muestra, String resultado, String informe) {

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

    private Escenario recorrerElCircuito() {
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));
        circuito.validar(resultado);
        String informe = circuito.crear(CircuitoDePrueba.informe(paciente, laboratorio, resultado));
        return new Escenario(paciente, muestra, resultado, informe);
    }

    // ── Corromper la proyección ──────────────────────────────────────────────────────────────────

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

    /** Un resultado publicado que ningún agregado respalda: el incidente de §15, reproducido. */
    private UUID publicarUnResultadoSinAgregado(Escenario escenario) {
        UUID inventado = UUID.randomUUID();
        Observation huerfano = new Observation();
        huerfano.setId(inventado.toString());
        huerfano.setStatus(Enumerations.ObservationStatus.FINAL);
        huerfano.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode("GLU")));
        huerfano.setSubject(new Reference("Patient/" + escenario.pacienteId()));
        huerfano.setValue(new Quantity()
                .setValue(new BigDecimal("77"))
                .setUnit("mg/dL")
                .setSystem(CircuitoDePrueba.UCUM)
                .setCode("mg/dL"));

        daos.getResourceDao(Observation.class).update(huerfano, comoElSistema);
        return inventado;
    }

    // ── Mirar la proyección ──────────────────────────────────────────────────────────────────────

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
            // `ResourceNotFoundException` si nunca estuvo, `ResourceGoneException` si se borró. Para
            // esta comprobación son lo mismo: no está publicado.
            return false;
        }
    }
}
