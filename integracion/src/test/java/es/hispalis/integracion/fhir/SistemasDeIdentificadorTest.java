package es.hispalis.integracion.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Los {@code system} viven ahora en tres sitios —la guía, el backend y el motor— y eso se pudre solo.
 *
 * <p>Este test es el gemelo del que ya tiene el backend, y hace falta uno por componente: cada uno
 * puede divergir por su cuenta. Equivocar un {@code system} <strong>no rompe nada visible</strong> —
 * el recurso valida, el laboratorio lo acepta— pero el paciente que el HIS manda deja de ser el mismo
 * que el laboratorio ya tenía, y acaban dos historias para una persona.
 */
class SistemasDeIdentificadorTest {

    private static final Path ALIASES = Path.of("..", "ig", "input", "fsh", "aliases.fsh");

    private static final Pattern ALIAS =
            Pattern.compile("^Alias:\\s+\\$(SID_\\w+)\\s*=\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    @Test
    void los_system_del_motor_son_los_que_declara_la_guia() throws IOException {
        Map<String, String> enLaGuia = aliasesDeLaGuia();

        assertThat(SistemasDeIdentificador.NHC).isEqualTo(enLaGuia.get("SID_NHC"));
        assertThat(SistemasDeIdentificador.DNI_NIE).isEqualTo(enLaGuia.get("SID_DNI_NIE"));
        assertThat(SistemasDeIdentificador.CIP_AUTONOMICO).isEqualTo(enLaGuia.get("SID_NUHSA"));
        assertThat(SistemasDeIdentificador.CIP_SNS).isEqualTo(enLaGuia.get("SID_CIP_SNS"));
        assertThat(SistemasDeIdentificador.NASS).isEqualTo(enLaGuia.get("SID_NASS"));
    }

    @Test
    void el_perfil_que_el_motor_declara_producir_existe_en_la_guia() throws IOException {
        Path perfil = Path.of("..", "ig", "input", "fsh", "profiles", "PacienteLabES.fsh");

        assertThat(Files.readString(perfil))
                .as("el motor pone `meta.profile` en cada Patient; si el id del perfil cambia, miente")
                .contains("Id: paciente-lab-es");
        assertThat(SistemasDeIdentificador.PERFIL_PACIENTE).endsWith("/paciente-lab-es");
    }

    private static Map<String, String> aliasesDeLaGuia() throws IOException {
        Matcher coincidencias = ALIAS.matcher(Files.readString(ALIASES));
        Map<String, String> aliases = new HashMap<>();
        while (coincidencias.find()) {
            aliases.put(coincidencias.group(1), coincidencias.group(2));
        }
        assertThat(aliases).as("no se han podido leer los alias de %s", ALIASES).isNotEmpty();
        return aliases;
    }
}
