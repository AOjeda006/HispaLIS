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
import org.hl7.fhir.r5.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
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

    /** Los proveedores que escribe este proyecto. Se pregunta a Spring: la lista no se escribe. */
    @Autowired
    private List<ProveedorPropio> proveedoresPropios;

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
    void metadata_declara_los_diez_perfiles_de_la_guia() {
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
     * Ningún recurso que este laboratorio gobierne puede quedarse sin describir en la guía.
     *
     * <p>Es la mitad que faltaba, y la que el filtrado por ruta de la CI hace invisible: los seis
     * <em>workflows</em> filtran por {@code paths:}, así que dar de alta un proveedor propio nuevo
     * dispara {@code ci-backend} y <strong>no</strong> {@code ci-ig}. La ausencia de cambios en
     * {@code ig/} no es una señal de que la guía siga al día: es lo que ocurre por construcción. El
     * hito 2 entero pasó sin tocar {@code ig/} y con un tipo de recurso nuevo publicado
     * ({@code Provenance}) del que la guía no decía una palabra.
     *
     * <p>El test cruza <strong>lo que el servidor declara</strong> —el {@code CapabilityStatement}
     * de verdad, no una lista— contra <strong>lo que la guía publica</strong>. El conjunto que se
     * exige no es el de los 158 tipos que HAPI declara por tener DAO: es el de los que este
     * proyecto <em>gobierna</em>, que son los que traen proveedor propio. Y esa lista
     * <strong>se deduce</strong> de los proveedores registrados, siguiendo la regla 4 de
     * {@code ADR-0014}: una lista escrita a mano nace correcta y envejece mal.
     */
    @Test
    void ningun_recurso_gobernado_por_el_laboratorio_se_queda_sin_perfil_en_la_guia() {
        CapabilityStatement conformidad = pedirConformidad();

        List<String> gobernados = proveedoresPropios.stream()
                .map(proveedor -> proveedor.getResourceType().getSimpleName())
                .distinct()
                .sorted()
                .toList();

        List<String> sinPerfil = gobernados.stream()
                .filter(tipo -> conformidad.getRestFirstRep().getResource().stream()
                        .filter(recurso -> tipo.equals(recurso.getType()))
                        .noneMatch(CapabilityStatementRestResourceComponent::hasSupportedProfile))
                .toList();

        assertThat(sinPerfil)
                .as(
                        """
                        El servidor publica y gobierna %s, y la guía no perfila %s.
                        Un tipo de recurso que el laboratorio escribe con sus propias reglas es contrato \
                        publicado: si la guía no lo describe, el único sitio donde está escrito lo que \
                        significa es el código. Añade el perfil en `ig/input/fsh/profiles/`, un ejemplo \
                        que lo declare en `meta.profile`, y la entrada en `PerfilesDeLaGuia`.""",
                        gobernados, sinPerfil)
                .isEmpty();
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
