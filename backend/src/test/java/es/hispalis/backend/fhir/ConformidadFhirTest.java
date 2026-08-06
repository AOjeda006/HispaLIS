package es.hispalis.backend.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hl7.fhir.r5.model.CapabilityStatement;
import org.hl7.fhir.r5.model.Enumerations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Criterio de aceptación 3 (§14 del diseño): el servidor declara lo que sabe hacer, y lo declara
 * bien.
 *
 * <p>Un {@code CapabilityStatement} equivocado es peor que no tenerlo: es la única forma que tiene
 * un cliente de descubrir el contrato sin preguntar, y si miente, miente en el sitio en el que
 * nadie va a dudar de él.
 */
class ConformidadFhirTest extends TestDeIntegracion {

    /** Ruta de los perfiles de la guía, desde el directorio de trabajo de Maven ({@code backend/}). */
    private static final Path PERFILES_DE_LA_IG = Path.of("..", "ig", "input", "fsh", "profiles");

    private static final Pattern ID_DEL_PERFIL = Pattern.compile("^Id:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
    private static final Pattern PADRE_DEL_PERFIL = Pattern.compile("^Parent:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Test
    void metadata_responde_200_y_declara_la_version_5_0_0() {
        CapabilityStatement conformidad = pedirConformidad();

        assertThat(conformidad.getFhirVersion()).isEqualTo(Enumerations.FHIRVersion._5_0_0);
        assertThat(conformidad.getStatus()).isEqualTo(Enumerations.PublicationStatus.ACTIVE);
    }

    /**
     * El contrato publicado no promete transacciones, porque este servidor no las cumple.
     *
     * <p>HAPI declara {@code transaction} por defecto y un <em>bundle</em> de solo lecturas hasta
     * funciona, pero lo que un cliente entiende al leerlo es «puedo escribir varios recursos y o
     * entran todos o no entra ninguno» — y eso lo rechaza el interceptor de {@code ADR-0014} con un
     * 422. Prometer la mitad que funciona hace que el límite se descubra fallando.
     */
    @Test
    void metadata_no_promete_transacciones_que_el_nucleo_rechaza() {
        CapabilityStatement conformidad = pedirConformidad();

        List<String> interacciones = conformidad.getRestFirstRep().getInteraction().stream()
                .map(interaccion -> interaccion.getCode().toCode())
                .toList();

        assertThat(interacciones)
                .as("declarar `transaction` es prometer atomicidad, y D22 dice que aquí no la hay")
                .doesNotContain("transaction");
    }

    @Test
    void metadata_declara_los_nueve_perfiles_de_la_guia() {
        CapabilityStatement conformidad = pedirConformidad();

        List<String> declarados = conformidad.getRestFirstRep().getResource().stream()
                .flatMap(recurso -> recurso.getSupportedProfile().stream())
                .map(canonica -> canonica.getValue())
                .toList();

        assertThat(declarados)
                .containsExactlyInAnyOrderElementsOf(Stream.of(PerfilesDeLaGuia.values())
                        .map(PerfilesDeLaGuia::canonica)
                        .toList());
    }

    @Test
    void cada_perfil_se_declara_bajo_el_recurso_que_perfila() {
        CapabilityStatement conformidad = pedirConformidad();

        for (PerfilesDeLaGuia perfil : PerfilesDeLaGuia.values()) {
            List<String> declarados = conformidad.getRestFirstRep().getResource().stream()
                    .filter(recurso -> perfil.tipoDeRecurso().equals(recurso.getType()))
                    .flatMap(recurso -> recurso.getSupportedProfile().stream())
                    .map(canonica -> canonica.getValue())
                    .toList();

            assertThat(declarados)
                    .as("el perfil %s debe declararse bajo %s", perfil.name(), perfil.tipoDeRecurso())
                    .contains(perfil.canonica());
        }
    }

    /**
     * La lista de perfiles del backend y la de la guía son la misma cosa escrita dos veces, y eso
     * se pudre solo. Este test las cruza contra el FSH —la fuente, no lo generado, para no depender
     * de que SUSHI haya corrido— y falla en cuanto divergen.
     */
    @Test
    void los_perfiles_del_backend_son_exactamente_los_de_la_guia() {
        Map<String, String> enLaGuia = leerPerfilesDeLaGuia();

        Map<String, String> enElBackend = Stream.of(PerfilesDeLaGuia.values())
                .collect(Collectors.toMap(PerfilesDeLaGuia::id, PerfilesDeLaGuia::tipoDeRecurso));

        assertThat(enElBackend)
                .as("si esto falla, la guía cambió y `PerfilesDeLaGuia` no: hay que actualizarla")
                .isEqualTo(enLaGuia);
    }

    /**
     * Pide la conformidad tal y como la pediría un cliente cualquiera: la URL pelada, sin
     * {@code _format}. Que responda JSON es parte de lo que se comprueba, no una comodidad del test.
     */
    private CapabilityStatement pedirConformidad() {
        ResponseEntity<String> respuesta = rest.getForEntity("/fhir/metadata", String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return contexto.newJsonParser().parseResource(CapabilityStatement.class, respuesta.getBody());
    }

    /** Lee los {@code .fsh} de la guía y devuelve {@code id del perfil → recurso que perfila}. */
    private static Map<String, String> leerPerfilesDeLaGuia() {
        try (Stream<Path> ficheros = Files.list(PERFILES_DE_LA_IG)) {
            return ficheros.filter(fichero -> fichero.toString().endsWith(".fsh"))
                    .collect(Collectors.toMap(
                            fichero -> extraer(ID_DEL_PERFIL, fichero), fichero -> extraer(PADRE_DEL_PERFIL, fichero)));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudieron leer los perfiles de la guía en " + PERFILES_DE_LA_IG, e);
        }
    }

    private static String extraer(Pattern patron, Path fichero) {
        try {
            Matcher coincidencia = patron.matcher(Files.readString(fichero));
            if (!coincidencia.find()) {
                throw new IllegalStateException("El perfil " + fichero.getFileName() + " no declara " + patron);
            }
            return coincidencia.group(1);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + fichero, e);
        }
    }
}
