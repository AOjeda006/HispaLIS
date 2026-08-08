package es.hispalis.backend.fhir.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.backend.fhir.seguridad.AmbitoSmart.Contexto;
import es.hispalis.backend.fhir.seguridad.AmbitoSmart.Permiso;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El intérprete de <em>scopes</em>: lo que no se entiende no concede nada.
 *
 * <p>Es un test de unidad y no de la API a propósito. Aquí se prueba la regla —qué significa cada
 * cadena—, y en {@code SeguridadSmartTest} se prueba que esa regla llega al cable. Meter los casos
 * raros de sintaxis en un test de integración costaría un arranque de Spring por cada uno y no diría
 * nada más.
 *
 * <p>El caso que justifica el fichero entero es {@code .dus}: la norma permite ignorar, sustituir o
 * rechazar un sufijo desordenado, y si se «corrigiera» a {@code .cud} un cliente que pidió actualizar
 * habría conseguido borrar.
 */
class AmbitoSmartTest {

    @Test
    void un_scope_de_paciente_concede_leer_y_buscar_sobre_todos_los_tipos() {
        AmbitoSmart ambito = interpretar("patient/*.rs");

        assertThat(ambito.contexto()).isEqualTo(Contexto.PACIENTE);
        assertThat(ambito.todosLosTipos()).isTrue();
        assertThat(ambito.permisos()).containsExactlyInAnyOrder(Permiso.LEER, Permiso.BUSCAR);
        assertThat(ambito.alcanza("Observation", Permiso.LEER)).isTrue();
        assertThat(ambito.alcanza("Observation", Permiso.CREAR)).isFalse();
    }

    @Test
    void un_scope_de_un_tipo_no_alcanza_a_otro() {
        AmbitoSmart ambito = interpretar("user/ServiceRequest.c");

        assertThat(ambito.alcanza("ServiceRequest", Permiso.CREAR)).isTrue();
        assertThat(ambito.alcanza("Observation", Permiso.CREAR)).isFalse();
    }

    @Test
    void los_cinco_sufijos_en_orden_se_entienden_todos() {
        assertThat(interpretar("system/*.cruds").permisos()).containsExactlyInAnyOrder(Permiso.values());
    }

    /**
     * Las tres formas de la v1, con la equivalencia que fija la propia norma.
     *
     * <p>No es cortesía con lo viejo: el documento de descubrimiento declara {@code permission-v1}, y
     * el día que estas tres dejaran de entenderse habría que dejar de declararla el mismo día.
     */
    @Test
    void las_formas_de_la_v1_significan_lo_que_dice_la_norma() {
        assertThat(interpretar("patient/Observation.read").permisos())
                .containsExactlyInAnyOrder(Permiso.LEER, Permiso.BUSCAR);
        assertThat(interpretar("user/Patient.write").permisos())
                .containsExactlyInAnyOrder(Permiso.CREAR, Permiso.ACTUALIZAR, Permiso.BORRAR);
        assertThat(interpretar("system/*.*").permisos()).containsExactlyInAnyOrder(Permiso.values());
    }

    /**
     * Lo que no se entiende no concede nada — y cada caso es una forma distinta de no entenderse.
     *
     * <ul>
     *   <li>{@code .dus} y {@code .sr}: desordenados. Aproximarlos concedería un verbo que nadie pidió.
     *   <li>{@code .rr}: repetido, que es el mismo fallo con otra cara.
     *   <li>{@code .x}: inventado.
     *   <li>{@code patient/observation.rs}: un tipo FHIR va en mayúscula; en minúscula no es un tipo.
     *   <li>{@code admin/*.rs}: contexto que no existe.
     *   <li>{@code patient/Observation.rs?category=…}: los scopes granulares son experimentales y este
     *       servidor no los implementa. Ignorar el {@code ?} sería lo peor: el cliente pidió acceso
     *       <strong>acotado</strong> y se le concedería el ancho.
     * </ul>
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "user/Observation.dus",
                "user/Observation.sr",
                "user/Observation.rr",
                "user/Observation.x",
                "patient/observation.rs",
                "admin/*.rs",
                "patient/Observation.rs?category=http://loinc.org|LP29684-5",
                "user/Observation.",
                "user/.rs",
                "patient*.rs"
            })
    void lo_que_no_se_entiende_no_concede_nada(String scope) {
        assertThat(AmbitoSmart.de(scope)).isEmpty();
    }

    /** Los scopes que no son permisos son legítimos y no dan acceso a datos: se ignoran sin ruido. */
    @ParameterizedTest
    @ValueSource(strings = {"openid", "profile", "fhirUser", "launch", "launch/patient", "offline_access"})
    void los_scopes_que_no_son_permisos_no_conceden_datos(String scope) {
        assertThat(AmbitoSmart.de(scope)).isEmpty();
    }

    @Test
    void un_testigo_reune_solo_los_scopes_que_concede_algo() {
        Testigo testigo = Testigo.de(
                "openid fhirUser launch user/*.rs user/Patient.c user/Observation.dus",
                null,
                "Practitioner/dra-alvarez",
                "f:1234");

        assertThat(testigo.ambitos()).hasSize(2);
        assertThat(testigo.actuaComo(Contexto.USUARIO)).isTrue();
        assertThat(testigo.limitadoAUnPaciente())
                .as("sin ámbito `patient/` no hay consentimiento de paciente que aplicar")
                .isFalse();
        assertThat(testigo.fhirUser()).contains("Practitioner/dra-alvarez");
    }

    /**
     * Un testigo {@code patient/} <strong>sin</strong> paciente en contexto sigue estando limitado a un
     * paciente. Es lo que hace que el consentimiento no lo deje pasar: la lectura amable —«sin
     * restricción, luego todo»— convertiría un error de configuración del servidor de identidad en una
     * fuga de datos.
     */
    @Test
    void un_testigo_de_paciente_sin_contexto_sigue_estando_limitado() {
        Testigo testigo = Testigo.de("patient/*.rs", null, null, "c:app");

        assertThat(testigo.limitadoAUnPaciente()).isTrue();
        assertThat(testigo.pacienteEnContexto()).isEmpty();
    }

    private static AmbitoSmart interpretar(String scope) {
        Optional<AmbitoSmart> ambito = AmbitoSmart.de(scope);
        assertThat(ambito).as("no se entendió el scope «%s»", scope).isPresent();
        return ambito.get();
    }
}
