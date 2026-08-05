package es.hispalis.backend.infraestructura.configuracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.resultado.RangoDeReferencia;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Los rangos que publica el laboratorio, cruzados contra el catálogo de pruebas de la guía.
 *
 * <p>El fichero de rangos es la fuente única y la comparten backend y generador, así que ya no pueden
 * divergir entre ellos. Lo que sí puede pasar es que se desvíen <strong>del catálogo</strong>: un
 * rango de una prueba que ya no existe, o —mucho peor— en una unidad distinta de aquella en la que el
 * laboratorio emite esa prueba. Una glucosa de 92 mg/dL contra un rango de 3,9 a 5,5 mmol/L sale
 * marcada como altísima sin que nada proteste: las dos cifras son correctas y la comparación no.
 *
 * <p>Se lee del FSH, que es la fuente, y no de {@code fsh-generated/}: así no depende de que SUSHI
 * haya corrido antes. Es el mismo criterio que {@code SistemasDeIdentificadorTest}.
 */
class CatalogoDeRangosEnFicheroTest {

    private static final Path CATALOGO_FSH = Path.of("..", "ig", "input", "fsh", "vocabulary", "CatalogoPruebas.fsh");

    /** Una prueba del catálogo: {@code * #GLU "Glucosa" "Glucosa en suero o plasma."} */
    private static final Pattern CONCEPTO = Pattern.compile("^\\* #(\\w+)\\s+\"", Pattern.MULTILINE);

    /** Su unidad: {@code * #GLU ^property[0].valueCoding = $UCUM#"mg/dL"} */
    private static final Pattern UNIDAD =
            Pattern.compile("^\\* #(\\w+) \\^property\\[0]\\.valueCoding = \\$UCUM#\"([^\"]+)\"", Pattern.MULTILINE);

    private final ObjectMapper json = new ObjectMapper();

    private final CatalogoDeRangosEnFichero catalogo =
            new CatalogoDeRangosEnFichero(new ClassPathResource("laboratorio/rangos-de-referencia.json"), json);

    @Test
    void los_rangos_solo_hablan_de_pruebas_que_el_catalogo_oferta() throws IOException {
        Set<String> delCatalogo = conceptosDeLaGuia();

        assertThat(delCatalogo).as("no se ha podido leer %s", CATALOGO_FSH).isNotEmpty();
        for (String prueba : pruebasConRango()) {
            assertThat(delCatalogo)
                    .as("el laboratorio publica un rango de «%s», que no está en su catálogo", prueba)
                    .contains(prueba);
        }
    }

    @Test
    void cada_rango_va_en_la_unidad_en_la_que_el_laboratorio_emite_esa_prueba() throws IOException {
        Map<String, String> unidades = unidadesDeLaGuia();

        for (String prueba : pruebasConRango()) {
            for (RangoDeReferencia rango : catalogo.buscarPorPrueba(prueba)) {
                assertThat(rango.unidadUcum())
                        .as(
                                "el rango de «%s» está en otra unidad que el resultado: se compararían cifras "
                                        + "que no son comparables",
                                prueba)
                        .isEqualTo(unidades.get(prueba));
            }
        }
    }

    @Test
    void una_prueba_cualitativa_no_tiene_rango() {
        // La PCR de tuberculosis se informa positiva o negativa. Un rango numérico ahí no significa
        // nada, y devolver una lista vacía es lo que permite que la proyección no publique ninguno.
        assertThat(catalogo.buscarPorPrueba("MTBPCR")).isEmpty();
    }

    @Test
    void el_rango_comun_va_antes_que_los_de_sexo() {
        assertThat(catalogo.buscarPorPrueba("GLU"))
                .singleElement()
                .satisfies(rango -> assertThat(rango.sexoAlQueAplica()).isEmpty());

        assertThat(catalogo.buscarPorPrueba("HB"))
                .as("la hemoglobina distingue por sexo: 13 g/dL es normal en una mujer y baja en un hombre")
                .hasSize(2)
                .allSatisfy(rango -> assertThat(rango.sexoAlQueAplica()).isNotEmpty());
    }

    /**
     * Lo garantizaban dos índices únicos parciales de PostgreSQL. Al salir de la base de datos, esa
     * garantía se habría perdido en silencio si no se comprobara al leer: duplicar una línea del
     * fichero no rompe el JSON.
     */
    @Test
    void dos_rangos_para_el_mismo_paciente_no_se_admiten() {
        Resource duplicado = deTexto(
                """
                { "rangos": [
                  { "prueba": "GLU", "bajo": 70, "alto": 100, "unidad": "mg/dL" },
                  { "prueba": "GLU", "bajo": 80, "alto": 110, "unidad": "mg/dL" }
                ] }
                """);

        assertThatThrownBy(() -> new CatalogoDeRangosEnFichero(duplicado, json))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("GLU");
    }

    @Test
    void un_rango_con_los_limites_al_reves_tampoco() {
        Resource alReves = deTexto(
                "{ \"rangos\": [ { \"prueba\": \"GLU\", \"bajo\": 100, \"alto\": 70," + " \"unidad\": \"mg/dL\" } ] }");

        assertThatThrownBy(() -> new CatalogoDeRangosEnFichero(alReves, json)).isInstanceOf(DatoInvalido.class);
    }

    @Test
    void un_sexo_que_no_es_un_sexo_se_rechaza_al_arrancar() {
        // Un `sexo` mal escrito no rompería nada visible: el rango simplemente no le aplicaría a
        // nadie, y el resultado saldría publicado sin rango de referencia.
        Resource raro = deTexto("{ \"rangos\": [ { \"prueba\": \"HB\", \"bajo\": 13.5, \"alto\": 17.5,"
                + " \"unidad\": \"g/dL\", \"sexo\": \"hombre\" } ] }");

        assertThatThrownBy(() -> new CatalogoDeRangosEnFichero(raro, json))
                .isInstanceOf(DatoInvalido.class)
                .hasMessageContaining("hombre");
    }

    private Set<String> pruebasConRango() throws IOException {
        Set<String> pruebas = new HashSet<>();
        Matcher filas = Pattern.compile("\"prueba\"\\s*:\\s*\"(\\w+)\"")
                .matcher(Files.readString(
                        Path.of("src", "main", "resources", "laboratorio", "rangos-de-referencia.json")));
        while (filas.find()) {
            pruebas.add(filas.group(1));
        }
        assertThat(pruebas).as("el fichero de rangos ha salido vacío").isNotEmpty();
        return pruebas;
    }

    private static Set<String> conceptosDeLaGuia() throws IOException {
        Set<String> codigos = new HashSet<>();
        Matcher conceptos = CONCEPTO.matcher(Files.readString(CATALOGO_FSH));
        while (conceptos.find()) {
            codigos.add(conceptos.group(1));
        }
        return codigos;
    }

    private static Map<String, String> unidadesDeLaGuia() throws IOException {
        Map<String, String> unidades = new HashMap<>();
        Matcher declaradas = UNIDAD.matcher(Files.readString(CATALOGO_FSH));
        while (declaradas.find()) {
            unidades.put(declaradas.group(1), declaradas.group(2));
        }
        assertThat(unidades)
                .as("no se han podido leer las unidades de %s", CATALOGO_FSH)
                .isNotEmpty();
        return unidades;
    }

    private static Resource deTexto(String contenido) {
        return new ByteArrayResource(contenido.getBytes(StandardCharsets.UTF_8));
    }
}
