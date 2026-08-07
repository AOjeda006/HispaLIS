package es.hispalis.integracion.terminologia;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import es.hispalis.integracion.infraestructura.terminologia.CatalogoDelServidorDeTerminologia;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Las cuatro operaciones contra el servidor de terminología <strong>de verdad</strong>.
 *
 * <p>El resto de tests del motor corren contra el arnés, y hacen bien: son rápidos y no necesitan
 * Docker. Pero un arnés lo escribe uno mismo, así que <em>no puede</em> demostrar que el cliente hable
 * como el servidor real espera — solo que hable como uno cree que espera. Este test cierra ese hueco.
 *
 * <p>Está apagado salvo que se le diga dónde mirar:
 *
 * <pre>{@code
 * docker compose -f infra/compose/docker-compose.yml up -d terminologia terminologia-carga
 * HISPALIS_TERMINOLOGIA_REAL=http://localhost:8086/fhir ./mvnw test -Dtest=ContraElServidorRealTest
 * }</pre>
 *
 * <p>No se enciende en la CI a propósito: arrancar el HAPI de terminología cuesta minuto y medio y
 * cargarlo, más. Encenderlo aquí convertiría cada empujón en una espera, y lo que este test vigila
 * —que el servidor no cambie de forma— cambia con las versiones, no con los commits.
 */
@EnabledIfEnvironmentVariable(
        named = "HISPALIS_TERMINOLOGIA_REAL",
        matches = ".+",
        disabledReason = "Necesita el servidor de terminología del compose; ver el comentario de la clase")
class ContraElServidorRealTest {

    private static CatalogoDelServidorDeTerminologia catalogo;

    @BeforeAll
    static void apuntarAlServidor() {
        FhirContext contexto = FhirContext.forR5();
        contexto.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        contexto.getRestfulClientFactory().setSocketTimeout(30_000);
        catalogo = new CatalogoDelServidorDeTerminologia(
                contexto.newRestfulGenericClient(System.getenv("HISPALIS_TERMINOLOGIA_REAL")));
    }

    @Test
    @DisplayName("$expand: el catálogo cargado tiene las 21 pruebas de la guía")
    void expand() {
        assertThat(catalogo.tamano()).isEqualTo(21);
    }

    @Test
    @DisplayName("$lookup: el nombre llega en español y la unidad, como propiedad del concepto")
    void lookup() {
        assertThat(catalogo.buscar("GLU").orElseThrow().display()).isEqualTo("Glucosa");
        assertThat(catalogo.buscar("GLU").orElseThrow().unidad()).contains("mg/dL");
        assertThat(catalogo.buscar("LEGIOAG").orElseThrow().display()).isEqualTo("Antígeno de Legionella en orina");
    }

    @Test
    @DisplayName("$translate: ida y vuelta, y la vuelta solo donde hay equivalencia")
    void translate() {
        assertThat(catalogo.buscar("GLU").orElseThrow().loinc()).isEqualTo("2345-7");
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, "2345-7"))
                .contains("GLU");
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, "4544-3"))
                .as("el hematocrito se mapea como «más amplio que», y eso no se invierte")
                .isEmpty();
    }

    @Test
    @DisplayName("$validate-code: los tipos de muestra salen del conjunto que publica la guía")
    void validateCode() {
        assertThat(catalogo.esTipoDeMuestraConocido("119364003")).isTrue();
        assertThat(catalogo.esTipoDeMuestraConocido("000000")).isFalse();
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.SYSTEM, "INVENTADO"))
                .isEmpty();
    }
}
