package es.hispalis.integracion.canal.adt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.integracion.TestDelMotor;
import es.hispalis.integracion.arnes.MensajesDePrueba;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.hl7.fhir.r5.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * El canal de demografía, de extremo a extremo: {@code ADT} entra por MLLP y sale un {@code Patient}
 * escrito por la API FHIR.
 *
 * <p>Recorre las tres garantías del motor —original guardado, deduplicación antes de escribir,
 * charset respetado— sobre el primer canal, que es donde tienen que quedar demostradas: si se dejan
 * para «cuando haya más canales», el segundo canal se escribe sin ellas.
 *
 * <p>Al terminar <strong>vuelca el {@code Patient} a {@code target/canal/}</strong>, igual que el
 * circuito del backend vuelca el suyo. Que el canal funcione y que lo que produce sea
 * <strong>conforme</strong> son dos cosas distintas, y la segunda solo la puede afirmar el validador
 * oficial de la especificación.
 */
class CanalAdtPacienteTest extends TestDelMotor {

    private static final Path VOLCADO = Path.of("target", "canal");

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(70_000_000);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void limpiarElArnes() {
        LABORATORIO.olvidarTodo();
    }

    @Test
    void un_a01_con_MUNOZ_entra_por_mllp_y_sale_como_paciente() {
        String nhc = siguienteNhc();
        String control = "MSG" + nhc;

        String acuse = elHis().enviar(
                        MensajesDePrueba.adt("A01", control, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");
        assertThat(LABORATORIO.altas()).hasSize(1);

        Patient publicado = LABORATORIO.altas().get(0);
        assertThat(publicado.getNameFirstRep().getFamily())
                .as("el apellido viaja entero: partirlo por el espacio confundiría pacientes")
                .isEqualTo(MensajesDePrueba.MUNOZ);
        assertThat(publicado.getNameFirstRep().getGiven())
                .extracting(org.hl7.fhir.r5.model.StringType::getValue)
                .containsExactly("Begoña", "María");
        assertThat(identificadores(publicado))
                .as("cada identificador va a su system según el tipo de la tabla 0203, no por su posición")
                .containsEntry(SistemasDeIdentificador.NHC, nhc)
                .containsEntry(SistemasDeIdentificador.DNI_NIE, "12345678Z")
                .containsEntry(SistemasDeIdentificador.CIP_AUTONOMICO, "AN0123456789");
        assertThat(publicado.getBirthDateElement().asStringValue()).isEqualTo("1981-03-14");
        assertThat(publicado.getGender()).isEqualTo(org.hl7.fhir.r5.model.Enumerations.AdministrativeGender.FEMALE);
        assertThat(publicado.getMeta().getProfile())
                .extracting(org.hl7.fhir.r5.model.CanonicalType::getValue)
                .containsExactly(SistemasDeIdentificador.PERFIL_PACIENTE);

        volcar("1-paciente-desde-adt", publicado);
    }

    /**
     * La prueba del charset, que es la que de verdad se gana o se pierde aquí.
     *
     * <p>El mismo nombre viaja por el cable en dos codificaciones distintas de verdad —el arnés
     * escribe los bytes según el {@code MSH-18} de cada mensaje— y tiene que llegar igual. Leer un
     * {@code 8859/1} como UTF-8 <strong>no lanza ninguna excepción</strong>: produce {@code MU?OZ} y
     * sigue, así que lo que se compara es la cadena, no la ausencia de error.
     */
    @ParameterizedTest(name = "{0} en {1}")
    @CsvSource({
        "MUÑOZ DE LA TORRE, 8859/1",
        "MUÑOZ DE LA TORRE, UNICODE UTF-8",
        "FERNÁNDEZ DE CÓRDOBA RUIZ, 8859/1",
        "FERNÁNDEZ DE CÓRDOBA RUIZ, UNICODE UTF-8",
        "PEÑA ÁLVAREZ, 8859/1",
        "PEÑA ÁLVAREZ, UNICODE UTF-8"
    })
    void los_apellidos_con_ene_y_tilde_llegan_intactos(String apellidos, String charset) {
        String nhc = siguienteNhc();

        String acuse =
                elHis().enviar(MensajesDePrueba.adt("A01", "MSG" + nhc, nhc, apellidos, "Begoña^María", charset));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AA");
        assertThat(LABORATORIO.altas())
                .singleElement()
                .extracting(paciente -> paciente.getNameFirstRep().getFamily())
                .isEqualTo(apellidos);
    }

    @Test
    void el_mismo_mensaje_dos_veces_solo_escribe_una() {
        String nhc = siguienteNhc();
        String mensaje = MensajesDePrueba.adt("A01", "MSG" + nhc, nhc, MensajesDePrueba.PENA, "Rocío^Ana", "8859/1");

        String primero = elHis().enviar(mensaje);
        String segundo = elHis().enviar(mensaje);

        assertThat(codigoDeAcuse(primero)).isEqualTo("AA");
        assertThat(codigoDeAcuse(segundo))
                .as("un duplicado se acusa AA: con un error el emisor lo reintentaría para siempre")
                .isEqualTo("AA");
        assertThat(LABORATORIO.escrituras())
                .as("el segundo mensaje no puede llegar al laboratorio: la deduplicación va antes de escribir")
                .isEqualTo(1);
    }

    @Test
    void el_original_queda_guardado_integro_y_localizable() {
        String nhc = siguienteNhc();
        String control = "MSG" + nhc;
        String mensaje = MensajesDePrueba.adt("A01", control, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1");

        elHis().enviar(mensaje);

        Map<String, Object> guardado = jdbc.queryForMap(
                """
                SELECT crudo, nhc, episodio, tipo, evento, estructura, version, charset_declarado, estado
                  FROM integracion.mensaje
                 WHERE aplicacion_emisora = :emisor AND control_id = :control
                """,
                new MapSqlParameterSource().addValue("emisor", "HIS_VIRGEN").addValue("control", control));

        assertThat(guardado.get("crudo"))
                .as("se archiva lo que llegó por el hilo, no una reserialización de lo parseado")
                .isEqualTo(mensaje);
        assertThat(guardado.get("nhc")).isEqualTo(nhc);
        assertThat(guardado.get("episodio")).isEqualTo("EP20260806001");
        assertThat(guardado.get("tipo")).isEqualTo("ADT");
        assertThat(guardado.get("evento")).isEqualTo("A01");
        assertThat(guardado.get("estructura")).isEqualTo("ADT_A01");
        assertThat(guardado.get("version")).isEqualTo("2.5.1");
        assertThat(guardado.get("charset_declarado")).isEqualTo("8859/1");
        assertThat(guardado.get("estado")).isEqualTo("PROCESADO");
    }

    @Test
    void un_a08_corrige_la_filiacion_del_paciente_que_ya_existe() {
        String nhc = siguienteNhc();
        elHis().enviar(MensajesDePrueba.adt("A01", "ALTA" + nhc, nhc, "MUNOZ DE LA TORRE", "Begoña^María", "8859/1"));

        String acuse = elHis().enviar(MensajesDePrueba.adt(
                "A08", "CORR" + nhc, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1"));

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AA");
        assertThat(LABORATORIO.altas()).hasSize(1);
        assertThat(LABORATORIO.correcciones())
                .singleElement()
                .extracting(paciente -> paciente.getNameFirstRep().getFamily())
                .isEqualTo(MensajesDePrueba.MUNOZ);
    }

    /**
     * Un {@code A08} no da de alta. Crear a partir de una corrección metería al paciente en el
     * laboratorio por la puerta de atrás, sin el {@code A01} que documenta por qué está.
     */
    @Test
    void un_a08_de_un_paciente_que_no_existe_no_lo_crea() {
        String nhc = siguienteNhc();

        String acuse = elHis().enviar(
                        MensajesDePrueba.adt("A08", "MSG" + nhc, nhc, MensajesDePrueba.PENA, "Rocío^Ana", "8859/1"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AE");
        assertThat(LABORATORIO.escrituras()).isZero();
        assertThat(estadoDe("MSG" + nhc)).isEqualTo("RECHAZADO");
    }

    /**
     * {@code ADT_A08} no existe en la tabla 0354, ni en V2.5 ni en V2.5.1. Ver
     * {@code docs/adr/adr-0018-…}: los eventos A01, A04, A08 y A13 comparten la estructura
     * {@code ADT_A01}.
     */
    @Test
    void una_estructura_que_no_existe_en_la_tabla_0354_se_rechaza_diciendo_cual_es_la_buena() {
        String nhc = siguienteNhc();

        String acuse = elHis().enviar(MensajesDePrueba.adt(
                "A08", "MSG" + nhc, nhc, MensajesDePrueba.PENA, "Rocío^Ana", "8859/1", "ADT_A08"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AR");
        assertThat(acuse).contains("ADT_A01");
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    @Test
    void un_charset_que_este_laboratorio_no_lee_se_rechaza_en_vez_de_corromper_el_nombre() {
        String nhc = siguienteNhc();

        String acuse = elHis().enviar(MensajesDePrueba.adt(
                "A01", "MSG" + nhc, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/8"));

        assertThat(codigoDeAcuse(acuse)).isEqualTo("AR");
        assertThat(acuse).contains("8859/8");
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    /**
     * El emisor mal configurado: {@code MSH-18} dice UTF-8 y por el cable viaja latín-1.
     *
     * <p>Es el caso que la trampa del charset produce a diario, y el que no lanza ninguna excepción
     * por sí solo. Aquí se caza porque al decodificar aparecen caracteres de reemplazo, y el motor
     * prefiere rechazar el mensaje a escribir {@code MU?OZ} en la historia de alguien.
     */
    @Test
    void un_emisor_que_declara_utf_8_y_manda_latin_1_no_escribe_un_nombre_corrupto() {
        String nhc = siguienteNhc();
        String mensaje =
                MensajesDePrueba.adt("A01", "MSG" + nhc, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "UNICODE UTF-8");

        String acuse = elHis().enviarComo(mensaje, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertThat(codigoDeAcuse(acuse)).as("acuse completo: %s", acuse).isEqualTo("AR");
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    /** El plano de sistemas va cifrado (D4). Un emisor en claro no debe poder entregar nada. */
    @Test
    void el_listener_no_atiende_en_claro() {
        String nhc = siguienteNhc();
        String mensaje =
                MensajesDePrueba.adt("A01", "MSG" + nhc, nhc, MensajesDePrueba.MUNOZ, "Begoña^María", "8859/1");

        assertThatThrownBy(() -> elHisSinCifrar().enviar(mensaje)).isInstanceOf(RuntimeException.class);
        assertThat(LABORATORIO.escrituras()).isZero();
    }

    private String estadoDe(String control) {
        return jdbc.queryForObject(
                "SELECT estado FROM integracion.mensaje WHERE control_id = :control",
                new MapSqlParameterSource("control", control),
                String.class);
    }

    private static Map<String, String> identificadores(Patient paciente) {
        return paciente.getIdentifier().stream()
                .collect(java.util.stream.Collectors.toMap(
                        org.hl7.fhir.r5.model.Identifier::getSystem, org.hl7.fhir.r5.model.Identifier::getValue));
    }

    /** {@code MSA-1} del acuse. */
    private static String codigoDeAcuse(String acuse) {
        List<String> segmentos = List.of(acuse.split("\r"));
        return segmentos.stream()
                .filter(segmento -> segmento.startsWith("MSA|"))
                .map(segmento -> segmento.split("\\|")[1])
                .findFirst()
                .orElseThrow(() -> new AssertionError("El acuse no trae MSA: " + acuse));
    }

    private void volcar(String nombre, Patient paciente) {
        try {
            Files.createDirectories(VOLCADO);
            Files.writeString(
                    VOLCADO.resolve(nombre + ".json"),
                    contexto.newJsonParser().setPrettyPrint(true).encodeResourceToString(paciente));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo volcar %s para el validador".formatted(nombre), e);
        }
    }

    private static String siguienteNhc() {
        return String.valueOf(SIGUIENTE.incrementAndGet());
    }
}
