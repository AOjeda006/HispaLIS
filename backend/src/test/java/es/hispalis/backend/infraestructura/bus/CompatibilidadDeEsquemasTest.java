package es.hispalis.backend.infraestructura.bus;

import static org.assertj.core.api.Assertions.assertThat;

import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import java.util.List;
import java.util.Set;
import org.apache.avro.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * El contrato del bus: los cuatro esquemas, su compatibilidad y lo que no pueden llevar dentro.
 *
 * <p>«Compatible hacia atrás» (§11) es una promesa concreta y comprobable: un consumidor que ya se
 * actualizó tiene que poder leer lo que se escribió antes. Declararlo en un fichero de configuración
 * no prueba nada — lo que prueba algo es intentar registrar una versión que la rompe y que el
 * registro diga que no.
 *
 * <p><strong>Contra un registro en memoria, no contra el servidor.</strong> En este equipo no hay
 * Docker (misma razón que el PostgreSQL embebido), así que no hay un Schema Registry de verdad
 * levantado. Lo que se usa es {@code MockSchemaRegistryClient}, que decide la compatibilidad con
 * <em>el mismo</em> {@code CompatibilityChecker} que ejecuta el servidor: la decisión es la del
 * registro, no una reimplementación. Lo que este test NO cubre es el camino HTTP hasta él, y queda
 * dicho aquí.
 */
class CompatibilidadDeEsquemasTest {

    private static final String AMBITO = "prueba-de-compatibilidad";

    /**
     * Los únicos campos que un hecho puede llevar además de sus referencias.
     *
     * <p>Que la lista esté aquí y no en el código de producción es deliberado: es la barrera contra
     * el {@code .avsc} que alguien añada mañana. El invariante 6 se incumple <em>escribiendo el
     * esquema</em>, y ese es el momento en el que este test tiene que estar en rojo.
     */
    private static final Set<String> CAMPOS_QUE_NO_SON_REFERENCIAS = Set.of("hechoId", "tipo", "ocurridoEn");

    @AfterEach
    void limpiarElRegistro() {
        MockSchemaRegistry.dropScope(AMBITO);
    }

    @Test
    void los_cuatro_topicos_registran_su_esquema_con_compatibilidad_hacia_atras() throws Exception {
        SchemaRegistryClient registro = MockSchemaRegistry.getClientForScope(AMBITO);

        new EsquemasDelBus(registro).asegurarRegistrados();

        assertThat(registro.getAllSubjects())
                .containsExactlyInAnyOrder(
                        "lab.peticiones.v1-value",
                        "lab.especimenes.v1-value",
                        "lab.resultados.v1-value",
                        "lab.informes.v1-value");
        for (Topico topico : Topico.values()) {
            assertThat(registro.getConfig(topico.sujetoEnElRegistro()).getCompatibilityLevel())
                    .as("el nivel se fija por sujeto, no solo en el servidor")
                    .isEqualTo(CompatibilityLevel.BACKWARD.name);
        }
    }

    /** Registrar dos veces lo mismo no crea una versión nueva: el relay lo llama en cada arranque. */
    @Test
    void registrar_los_mismos_esquemas_otra_vez_no_crea_versiones() throws Exception {
        SchemaRegistryClient registro = MockSchemaRegistry.getClientForScope(AMBITO);

        new EsquemasDelBus(registro).asegurarRegistrados();
        new EsquemasDelBus(registro).asegurarRegistrados();

        for (Topico topico : Topico.values()) {
            assertThat(registro.getAllVersions(topico.sujetoEnElRegistro())).hasSize(1);
        }
    }

    /**
     * El cambio que rompe: un campo obligatorio nuevo.
     *
     * <p>Un consumidor con este esquema leyendo un mensaje escrito con el anterior no encuentra el
     * campo y no tiene con qué rellenarlo — no hay valor por defecto—, así que la lectura falla. Es
     * el error más fácil de cometer y el que un tópico sin registro no detecta hasta producción.
     */
    @Test
    void una_version_con_un_campo_obligatorio_nuevo_la_rechaza_el_registro() throws Exception {
        SchemaRegistryClient registro = registroConLosEsquemasPuestos();
        String sujeto = Topico.RESULTADOS.sujetoEnElRegistro();

        AvroSchema conCampoObligatorio =
                variandoLosResultados("""
                {"name": "laboratorioRef", "type": "string"}""");

        assertThat(registro.testCompatibility(sujeto, conCampoObligatorio)).isFalse();
        assertThat(registro.testCompatibilityVerbose(sujeto, conCampoObligatorio))
                .as("y además dice por qué, que es lo que se lee cuando falla el despliegue")
                .isNotEmpty();
    }

    /** El cambio que no rompe: un campo nuevo con valor por defecto. Así se evoluciona un tópico. */
    @Test
    void una_version_con_un_campo_opcional_nuevo_la_acepta_el_registro() throws Exception {
        SchemaRegistryClient registro = registroConLosEsquemasPuestos();
        String sujeto = Topico.RESULTADOS.sujetoEnElRegistro();

        AvroSchema conCampoOpcional = variandoLosResultados(
                """
                {"name": "laboratorioRef", "type": ["null", "string"], "default": null}""");

        assertThat(registro.testCompatibility(sujeto, conCampoOpcional)).isTrue();
    }

    /**
     * El invariante 6, convertido en propiedad del contrato.
     *
     * <p>Un campo que no sea el identificador del hecho, su tipo, su marca de tiempo o una referencia
     * es, casi por definición, un dato del paciente colándose en el bus. La comprobación es
     * estructural —por la forma del nombre— y no una lista de palabras prohibidas: «nombre»,
     * «apellidos» o «nhc» se pueden enumerar, pero «valor», «unidad» o «diagnostico» también son PHI
     * y nadie se acuerda de todas.
     */
    @Test
    void ningun_esquema_declara_un_campo_que_no_sea_una_referencia() {
        for (Topico topico : Topico.values()) {
            List<String> sospechosos = topico.esquema().getFields().stream()
                    .map(Schema.Field::name)
                    .filter(campo -> !CAMPOS_QUE_NO_SON_REFERENCIAS.contains(campo))
                    .filter(campo -> !campo.equals("pacienteId"))
                    .filter(campo -> !campo.endsWith("Ref"))
                    .toList();

            assertThat(sospechosos)
                    .as("%s declara campos que no son referencias: el bus publica identidades, no datos", topico)
                    .isEmpty();
        }
    }

    private static SchemaRegistryClient registroConLosEsquemasPuestos() {
        SchemaRegistryClient registro = MockSchemaRegistry.getClientForScope(AMBITO);
        new EsquemasDelBus(registro).asegurarRegistrados();
        return registro;
    }

    /**
     * El esquema de {@code lab.resultados.v1} con un campo más. Se parte del esquema real y no de una
     * copia escrita a mano: si el {@code .avsc} cambia, este test sigue midiendo lo que hay.
     */
    private static AvroSchema variandoLosResultados(String campoNuevo) {
        String original = Topico.RESULTADOS.esquema().toString();
        int cierreDeLosCampos = original.lastIndexOf("]}");
        return new AvroSchema(original.substring(0, cierreDeLosCampos) + "," + campoNuevo + "]}");
    }
}
