package es.hispalis.backend.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r5.model.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * El {@code outbox} transaccional: los hechos que el laboratorio deja apuntados para publicar.
 *
 * <p>El bus no puede publicar nada que no esté escrito, y escribirlo <strong>después</strong> de
 * confirmar la transacción es perder hechos en cuanto algo se caiga entre las dos operaciones. Por
 * eso el hecho se escribe en la <strong>misma transacción</strong> que el dominio y la proyección
 * (§9): o entra todo, o no entra nada.
 *
 * <p>Se prueba <strong>por el lado del fallo</strong>. Un test del camino feliz pasaría igual con dos
 * transacciones separadas y no demostraría nada; el que sí demuestra algo es el que provoca un fallo
 * <em>después</em> de que el hecho se haya escrito y comprueba que no queda rastro.
 *
 * <p>Y lo que de verdad hay que vigilar: que el hecho <strong>no lleve PHI</strong>. El invariante 6
 * del proyecto prohíbe datos clínicos y filiativos en el bus, y el sitio donde eso se incumple es
 * aquí, construyendo la carga — no en Kafka. Un hecho es {@code { pacienteId, peticionId,
 * observationRef, … }}: referencias, y nada más. Lo que sale por el tópico ya construido lo vuelve a
 * comprobar {@code RelayDelOutboxTest}, sobre los bytes reales.
 */
class OutboxTransaccionalTest extends TestDeIntegracion {

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private CircuitoDePrueba circuito;

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
    }

    @Test
    void cada_escritura_del_circuito_deja_su_hecho() {
        String paciente = recorrerElCircuitoEntero();

        assertThat(tiposDe(paciente))
                .as("cada paso del circuito es un hecho que alguien de fuera necesita conocer")
                .containsExactly(
                        "PACIENTE_REGISTRADO",
                        "PETICION_REGISTRADA",
                        "ESPECIMEN_REGISTRADO",
                        "RESULTADO_INFORMADO",
                        "RESULTADO_VALIDADO",
                        "INFORME_EMITIDO");
    }

    /**
     * La prueba de que es una sola transacción. Este resultado supera al dominio —la muestra está
     * disponible y la línea activa—, así que el hecho ya está escrito cuando la proyección lo rechaza
     * por apuntar a un laboratorio que no existe. Con dos transacciones, el hecho sobreviviría y el
     * bus anunciaría un resultado que no llegó a publicarse nunca.
     */
    @Test
    void un_fallo_posterior_al_hecho_no_deja_rastro_en_el_outbox() {
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));

        Observation aFantasma = CircuitoDePrueba.resultado(paciente, muestra, linea, "Organization/no-existe");
        ResponseEntity<String> rechazado = circuito.enviar(aFantasma);

        assertThat(rechazado.getStatusCode().is2xxSuccessful())
                .as("cuerpo: %s", rechazado.getBody())
                .isFalse();
        assertThat(tiposDe(paciente))
                .as("el hecho se escribió antes del fallo: si sobrevive, no era la misma transacción")
                .doesNotContain("RESULTADO_INFORMADO");
    }

    @Test
    void un_alta_que_el_dominio_rechaza_no_deja_hecho() {
        String nhc = CircuitoDePrueba.siguienteNhc();
        String paciente = circuito.crear(CircuitoDePrueba.paciente(nhc));

        // El mismo NHC otra vez: el laboratorio no admite dos pacientes con el mismo número.
        ResponseEntity<String> duplicado = circuito.enviar(CircuitoDePrueba.paciente(nhc));

        assertThat(duplicado.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tiposDe(paciente)).containsExactly("PACIENTE_REGISTRADO");
    }

    /**
     * El invariante 6, comprobado donde se incumple. Ni el nombre, ni el NHC, ni el DNI, ni el NUHSA
     * pueden estar en la carga de ningún hecho: el bus publica referencias, no historias clínicas.
     */
    @Test
    void ningun_hecho_lleva_phi() {
        String nhc = CircuitoDePrueba.siguienteNhc();
        String paciente = recorrerElCircuitoEntero(nhc);

        List<String> cargas = cargasDe(paciente);

        assertThat(cargas).isNotEmpty();
        assertThat(cargas)
                .as("la carga de un hecho son referencias; cualquier otra cosa es una fuga")
                .noneMatch(carga -> carga.contains(nhc)
                        || carga.contains(CircuitoDePrueba.APELLIDOS)
                        || carga.contains(CircuitoDePrueba.NOMBRE_DE_PILA)
                        || carga.contains(CircuitoDePrueba.DNI)
                        || carga.contains(CircuitoDePrueba.NUHSA));
    }

    /** La clave de partición es el paciente (§9): así todo lo suyo se consume en orden. */
    @Test
    void todos_los_hechos_de_un_paciente_comparten_clave_de_particion() {
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        circuito.crear(CircuitoDePrueba.muestra(paciente));

        List<String> claves = jdbc.queryForList(
                "SELECT DISTINCT clave_de_particion::text FROM outbox.hecho WHERE clave_de_particion = :paciente",
                new MapSqlParameterSource("paciente", UUID.fromString(CircuitoDePrueba.identidadDe(paciente))),
                String.class);

        assertThat(claves).containsExactly(CircuitoDePrueba.identidadDe(paciente));
    }

    /** Nada nace publicado: el relay es quien marca la fecha, y en estos tests va apagado. */
    @Test
    void un_hecho_recien_escrito_esta_sin_publicar() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));

        List<Map<String, Object>> hechos = hechosDe(paciente);

        assertThat(hechos).hasSize(1);
        assertThat(hechos.get(0).get("publicado_en")).isNull();
        assertThat(hechos.get(0).get("descartado_en")).isNull();
        assertThat(hechos.get(0).get("creado_en")).isNotNull();
    }

    private String recorrerElCircuitoEntero() {
        return recorrerElCircuitoEntero(CircuitoDePrueba.siguienteNhc());
    }

    private String recorrerElCircuitoEntero(String nhc) {
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String paciente = circuito.crear(CircuitoDePrueba.paciente(nhc));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));
        circuito.validar(resultado);
        circuito.crear(CircuitoDePrueba.informe(paciente, laboratorio, resultado));
        return paciente;
    }

    private List<String> tiposDe(String paciente) {
        return hechosDe(paciente).stream()
                .map(hecho -> (String) hecho.get("tipo"))
                .toList();
    }

    private List<String> cargasDe(String paciente) {
        return hechosDe(paciente).stream()
                .map(hecho -> (String) hecho.get("carga"))
                .toList();
    }

    private List<Map<String, Object>> hechosDe(String paciente) {
        return jdbc.queryForList(
                """
                SELECT tipo, carga::text AS carga, creado_en, publicado_en, descartado_en
                  FROM outbox.hecho
                 WHERE clave_de_particion = :paciente
                 ORDER BY creado_en, tipo
                """,
                new MapSqlParameterSource("paciente", UUID.fromString(CircuitoDePrueba.identidadDe(paciente))));
    }
}
