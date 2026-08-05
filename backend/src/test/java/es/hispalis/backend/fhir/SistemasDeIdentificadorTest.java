package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Los {@code system} de identificador viven a la vez en la guía y en el backend, y eso se pudre
 * solo. Este test los cruza contra el FSH y falla en cuanto divergen.
 *
 * <p>Equivocar un {@code system} no rompe nada de forma visible: el recurso valida, se guarda y se
 * devuelve. Lo que pasa es que <strong>el identificador deja de significar lo que dice</strong>, las
 * búsquedas por él no encuentran nada y dos pacientes distintos pueden parecer el mismo. Es un fallo
 * silencioso, que es la razón de que merezca un test propio.
 *
 * <p>Se lee del FSH, que es la fuente, y no de {@code fsh-generated/}: así no depende de que SUSHI
 * haya corrido antes.
 */
class SistemasDeIdentificadorTest {

    private static final Path ALIASES = Path.of("..", "ig", "input", "fsh", "aliases.fsh");

    private static final Pattern ALIAS =
            Pattern.compile("^Alias:\\s+\\$(SID_\\w+)\\s*=\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    @Test
    void los_system_del_backend_son_los_que_declara_la_guia() throws IOException {
        Map<String, String> enLaGuia = aliasesDeLaGuia();

        assertThat(SistemasDeIdentificador.NHC).isEqualTo(enLaGuia.get("SID_NHC"));
        assertThat(SistemasDeIdentificador.DNI_NIE).isEqualTo(enLaGuia.get("SID_DNI_NIE"));
        assertThat(SistemasDeIdentificador.CIP_AUTONOMICO).isEqualTo(enLaGuia.get("SID_NUHSA"));
        assertThat(SistemasDeIdentificador.CIP_SNS).isEqualTo(enLaGuia.get("SID_CIP_SNS"));
        assertThat(SistemasDeIdentificador.NASS).isEqualTo(enLaGuia.get("SID_NASS"));
    }

    @Test
    void los_dos_oid_del_ministerio_se_mantienen_adoptados() {
        // D21: son los que usa la guía de ÚNICAS. Si alguien los cambiara por unos propios, HispaLIS
        // estaría contradiciendo al Ministerio en los dos únicos identificadores que él sí publica.
        assertThat(SistemasDeIdentificador.DNI_NIE).startsWith("urn:oid:");
        assertThat(SistemasDeIdentificador.CIP_SNS).startsWith("urn:oid:");
    }

    private static Map<String, String> aliasesDeLaGuia() throws IOException {
        Matcher coincidencias = ALIAS.matcher(Files.readString(ALIASES));
        Map<String, String> aliases = new java.util.HashMap<>();
        while (coincidencias.find()) {
            aliases.put(coincidencias.group(1), coincidencias.group(2));
        }
        assertThat(aliases).as("no se han podido leer los alias de %s", ALIASES).isNotEmpty();
        return aliases;
    }
}
