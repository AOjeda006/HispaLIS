package es.hispalis.backend.fhir.exportacion;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.seguridad.ServidorDeIdentidadDePruebas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Quién puede exportar — que en Bulk Data no es la misma pregunta que en una lectura.
 *
 * <p>Un {@code GET /fhir/Patient/123} entrega una historia; un {@code $export} entrega la población de
 * una enfermedad en un fichero que después vive en un disco. Por eso la puerta no es «leer
 * {@code Observation}», sino <strong>los dos permisos a la vez y desde un cliente de sistema</strong>:
 * {@code system/Group.rs} —la IG de Bulk Data exige autorización sobre el propio {@code Group}— y
 * {@code system/*.rs} sobre todos los tipos, que es lo que de verdad se lleva. Es la misma forma de la
 * regla de {@code $reconciliar} (ítem 35), y por la misma razón: <strong>ningún cliente del
 * <em>realm</em> lo tiene concedido de fábrica</strong>, así que dárselo a alguien es un acto
 * explícito de quien administra la identidad.
 *
 * <p><strong>El 403 va antes que el 404.</strong> Se comprueba a propósito con una cohorte que no
 * existe: si el servidor contestara «no existe» a quien no está autorizado, un cliente cualquiera
 * podría averiguar de qué enfermedades tiene cohorte el laboratorio probando nombres. Qué cohortes hay
 * es, en sí mismo, información epidemiológica.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=true",
            "hispalis.seguridad.audiencias=" + ServidorDeIdentidadDePruebas.AUDIENCIA,
            "hispalis.seguridad.tiempo-de-espera=PT2S",
            "hispalis.exportacion.habilitada=true",
            // Y las dos vueltas de fondo, apagadas: esta clase declara su propio
            // `@SpringBootTest` y ese oculta el del padre ENTERO, así que sin repetirlas se
            // quedan con el valor de producción —encendidas— y este contexto se pone a
            // consumir el mismo outbox que el contexto del test que sí las prueba. Spring
            // cachea los contextos: el de esta clase sigue vivo cuando corre `NotificadorEdoTest`,
            // le quita el hecho y lo descarta con SU catálogo, que no declara nada.
            "hispalis.edo.habilitado=false",
            "hispalis.notificaciones.habilitado=false"
        })
class ExportacionAutorizadaTest extends TestDeIntegracion {

    private static final ServidorDeIdentidadDePruebas IDENTIDAD = ServidorDeIdentidadDePruebas.elDeSiempre();

    /** Los dos permisos, juntos y desde un cliente de sistema. Es el único que abre la puerta. */
    private static final String EL_QUE_EXPORTA = "system/Group.rs system/*.rs";

    /**
     * Una cohorte que <strong>no existe y no la abre nadie</strong>, y tiene que ser así.
     *
     * <p>Lo que se prueba aquí es la puerta, no la exportación, y usar una cohorte real haría el test
     * dependiente de qué otra clase haya corrido antes: los tests comparten base de datos, y
     * `ExportacionMasivaTest` abre la de la legionelosis. Con una que nunca existe, el `404` del último
     * caso significa exactamente lo que dice — se pasó la autorización— y no «hoy no había datos».
     */
    private static final String UNA_COHORTE = "/fhir/Group/cohorte-que-no-abre-nadie/$export";

    private static Path directorio;

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void prepararElDisco() throws IOException {
        directorio = Files.createTempDirectory("hispalis-exportaciones-autorizadas");
    }

    @DynamicPropertySource
    static void apuntarAlServidorDeIdentidad(DynamicPropertyRegistry registro) {
        registro.add("hispalis.seguridad.emisor", IDENTIDAD::emisor);
        registro.add("hispalis.exportacion.directorio", () -> directorio.toString());
    }

    @Test
    @DisplayName("sin testigo no se exporta")
    void sinTestigoNoSeExporta() {
        ResponseEntity<String> respuesta =
                rest.exchange(UNA_COHORTE, HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Leer el grupo no es exportarlo: lo que sale por {@code $export} son los datos de sus miembros. */
    @Test
    @DisplayName("`system/Group.rs` a secas no exporta: no alcanza a lo que el fichero se lleva")
    void soloConElGrupoNoSeExporta() {
        assertThat(exportarCon("system/Group.rs").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Y leerlo todo tampoco, si no se está autorizado sobre la cohorte. Son dos permisos, no uno. */
    @Test
    @DisplayName("`system/*.rs` sin permiso sobre `Group` tampoco exporta")
    void soloConLosRecursosNoSeExporta() {
        assertThat(exportarCon("system/Observation.rs system/Patient.rs").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Y un usuario tampoco, por muchos permisos que traiga.
     *
     * <p>{@code user/*.cruds} es más de lo que tiene ningún facultativo, y aun así no exporta: una
     * exportación masiva no es un acto asistencial: nadie atiende a doscientas personas a la vez.
     */
    @Test
    @DisplayName("un testigo de usuario no exporta ni con permiso total")
    void unUsuarioNoExporta() {
        String deLaFacultativa = IDENTIDAD.testigo("dra.alvarez", "user/*.cruds", null, "Practitioner/dra-alvarez");

        assertThat(lanzar(deLaFacultativa).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Con los dos permisos sí se pasa la puerta — y entonces, y solo entonces, se sabe si la cohorte
     * existe.
     */
    @Test
    @DisplayName("con los dos permisos se pasa la puerta, y la cohorte inexistente es 404 y no 403")
    void conLosDosPermisosSePasaLaPuerta() {
        ResponseEntity<String> respuesta = exportarCon(EL_QUE_EXPORTA);

        assertThat(respuesta.getStatusCode())
                .as("autorizado: a partir de aquí la respuesta habla de la cohorte, no del cliente")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> exportarCon(String scopes) {
        return lanzar(IDENTIDAD.testigo("almacen-analitico", scopes, null, null));
    }

    private ResponseEntity<String> lanzar(String testigo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(testigo);
        cabeceras.add("Prefer", "respond-async");
        return rest.exchange(UNA_COHORTE, HttpMethod.POST, new HttpEntity<>(cabeceras), String.class);
    }
}
