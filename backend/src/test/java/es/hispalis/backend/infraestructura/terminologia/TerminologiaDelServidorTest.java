package es.hispalis.backend.infraestructura.terminologia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.resultado.NoSeSabeSiEsCritico;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.DecimalType;
import org.hl7.fhir.r5.model.Parameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que este cliente pone en el cable y lo que hace con lo que vuelve.
 *
 * <p>El servidor de aquí <strong>no es un servidor de terminología</strong>: es un oído puesto en el
 * puerto que apunta lo que le llega y contesta lo que se le ha dicho. Sirve para lo único que un
 * servidor de verdad no deja comprobar — <em>qué</em> se manda—, y para provocar a voluntad la caída
 * que en producción no se puede ensayar. Que las cuatro operaciones existen y responden está probado
 * contra el HAPI del {@code compose}, que es donde eso se prueba.
 */
class TerminologiaDelServidorTest {

    private static final FhirContext R5 = FhirContext.forR5();
    private static final String LOINC = "http://loinc.org";

    private HttpServer servidor;
    private OidoEnElPuerto oido;
    private TerminologiaDelServidor terminologia;

    @BeforeEach
    void levantarElOido() throws IOException {
        oido = new OidoEnElPuerto();
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.createContext("/fhir", oido);
        servidor.start();
        terminologia = new TerminologiaDelServidor(clienteContra(servidor));
    }

    @AfterEach
    void bajarlo() {
        servidor.stop(0);
    }

    @Test
    @DisplayName("$translate va con los nombres de parámetro de R5, no con los de R4")
    void translateEnR5() {
        oido.contesta("$translate", traducido("2345-7", "Glucose [Mass/volume] in Serum or Plasma", "equivalent"));
        oido.contesta("$lookup", conParametro("display", "Glucosa"));

        terminologia.pruebaDelCatalogo("GLU");

        Parameters enviado = oido.loQueLlegoA("$translate");
        // `code` y `targetsystem` son de R4. HAPI acepta los dos, así que copiar un ejemplo de R4
        // funciona aquí y falla contra un servidor estricto: el fallo más caro de encontrar.
        assertThat(nombresDe(enviado)).containsExactlyInAnyOrder("url", "system", "sourceCode", "targetSystem");
        assertThat(valorDe(enviado, "sourceCode")).isEqualTo("GLU");
        assertThat(valorDe(enviado, "targetSystem")).isEqualTo(LOINC);
    }

    @Test
    @DisplayName("el nombre español manda en el `text`, y el LOINC llega con el suyo en inglés")
    void elNombreEspanolMandaEnElTexto() {
        oido.contesta("$lookup", conParametro("display", "Glucosa"));
        oido.contesta("$translate", traducido("2345-7", "Glucose [Mass/volume] in Serum or Plasma", "equivalent"));

        CodeableConcept concepto = terminologia.pruebaDelCatalogo("GLU");

        assertThat(concepto.getText()).isEqualTo("Glucosa");
        assertThat(concepto.getCoding()).hasSize(2);
        assertThat(concepto.getCoding().get(0).getSystem()).isEqualTo(CatalogoDePruebas.SYSTEM);
        assertThat(concepto.getCoding().get(0).getDisplay()).isEqualTo("Glucosa");
        // El de LOINC se copia tal cual llega: su licencia prohíbe alterar el contenido del campo.
        assertThat(concepto.getCoding().get(1).getSystem()).isEqualTo(LOINC);
        assertThat(concepto.getCoding().get(1).getCode()).isEqualTo("2345-7");
        assertThat(concepto.getCoding().get(1).getDisplay()).isEqualTo("Glucose [Mass/volume] in Serum or Plasma");
    }

    @Test
    @DisplayName("un LOINC más estrecho que la prueba no se publica como si fuera lo mismo")
    void loQueNoEsEquivalenteNoSePublica() {
        oido.contesta("$lookup", conParametro("display", "Hematocrito"));
        oido.contesta(
                "$translate",
                traducido("4544-3", "Hematocrit [Volume Fraction] of Blood", "source-is-broader-than-target"));

        CodeableConcept concepto = terminologia.pruebaDelCatalogo("HTO");

        // `HTO → 4544-3` dice «el LOINC es el del contador automático y la prueba no lo declara».
        // Publicarlo sin más afirmaría un método que el laboratorio no ha dicho que use.
        assertThat(concepto.getCoding()).hasSize(1);
        assertThat(concepto.getCoding().get(0).getSystem()).isEqualTo(CatalogoDePruebas.SYSTEM);
    }

    @Test
    @DisplayName("HAPI aún contesta `equivalence` de R4, y también hay que entenderlo")
    void tambienSeLeeLaFormaDeR4() {
        oido.contesta("$lookup", conParametro("display", "TSH (tirotropina)"));
        Parameters comoR4 = new Parameters();
        comoR4.addParameter("result", true);
        Parameters.ParametersParameterComponent emparejamiento =
                comoR4.addParameter().setName("match");
        emparejamiento.addPart().setName("equivalence").setValue(new org.hl7.fhir.r5.model.CodeType("equivalent"));
        emparejamiento
                .addPart()
                .setName("concept")
                .setValue(new org.hl7.fhir.r5.model.Coding(LOINC, "3016-3", "Thyrotropin"));
        oido.contesta("$translate", comoR4);

        CodeableConcept concepto = terminologia.pruebaDelCatalogo("TSH");

        assertThat(concepto.getCoding()).hasSize(2);
        assertThat(concepto.getCoding().get(1).getCode()).isEqualTo("3016-3");
    }

    @Test
    @DisplayName("una prueba que el conjunto no contiene se rechaza")
    void laPruebaQueNoEstaSeRechaza() {
        oido.contesta("$validate-code", conResultado(false));

        assertThatThrownBy(() -> terminologia.exigirQueLaPruebaExiste("NOEXISTE"))
                .isInstanceOf(ReglaDeNegocioIncumplida.class)
                .hasMessageContaining("NOEXISTE");

        Parameters enviado = oido.loQueLlegoA("$validate-code");
        assertThat(valorDe(enviado, "url")).endsWith("/ValueSet/pruebas-del-catalogo");
        assertThat(valorDe(enviado, "system")).isEqualTo(CatalogoDePruebas.SYSTEM);
    }

    @Test
    @DisplayName("con el servidor caído el laboratorio sigue: código sin nombre y nada que rechazar")
    void sinServidorElLaboratorioSigue() {
        servidor.stop(0);

        CodeableConcept concepto = terminologia.pruebaDelCatalogo("GLU");

        assertThat(concepto.getCoding()).hasSize(1);
        assertThat(concepto.getCoding().get(0).getCode()).isEqualTo("GLU");
        assertThat(concepto.getCoding().get(0).hasDisplay()).isFalse();
        // Y sobre todo: no se rechaza nada. Un servidor de terminología caído no puede convertir
        // cada resultado del laboratorio en un 422.
        terminologia.exigirQueLaPruebaExiste("GLU");
    }

    @Test
    @DisplayName("con el servidor caído NO se contesta «no es crítico»: se dice que no se sabe")
    void conElServidorCaidoNoSeContestaQueNoEsCritico() {
        servidor.stop(0);

        // Es la única pregunta de este cliente que no degrada, y el motivo está en la respuesta
        // contraria: «no es crítico» no es una versión pobre de «no lo sé», es lo opuesto, y se paga
        // con una llamada de teléfono que no se hace.
        assertThatThrownBy(() -> terminologia.esCritico("K", new BigDecimal("7.5"), "mmol/L"))
                .isInstanceOf(NoSeSabeSiEsCritico.class)
                .hasMessageContaining("K");
    }

    @Test
    @DisplayName("un límite publicado sin procedencia no se usa: se para y se dice cuál está mal")
    void unLimiteSinProcedenciaNoSeUsa() {
        oido.contesta(
                "$lookup",
                conPropiedades(
                        propiedad(
                                "unidad-ucum",
                                new org.hl7.fhir.r5.model.Coding("http://unitsofmeasure.org", "mmol/L", null)),
                        propiedad("limite-critico-alto", new DecimalType("6.3"))));

        assertThatThrownBy(() -> terminologia.umbralDe("K"))
                .isInstanceOf(NoSeSabeSiEsCritico.class)
                .hasMessageContaining("K")
                .hasMessageContaining("de dónde sale");
    }

    @Test
    @DisplayName("una prueba sin ningún límite publicado no tiene umbral, y eso sí es una respuesta")
    void laPruebaSinLimitesNoTieneUmbral() {
        oido.contesta("$lookup", conParametro("display", "Glucosa"));

        assertThat(terminologia.umbralDe("GLU")).isEmpty();
        assertThat(terminologia.esCritico("GLU", new BigDecimal("600"), "mg/dL"))
                .isFalse();
    }

    /**
     * El nombre de la enfermedad sale de la ENFERMEDAD, no de quien la señala.
     *
     * <p>Esto lo cazó un ensayo en vivo y ningún test: el {@code valueCoding} con el que
     * {@code catalogo-pruebas} apunta a la enfermedad <strong>no lleva {@code display}</strong> —el FSH
     * escribe {@code EnfermedadesEdo#LEGIONELOSIS}, que es sistema y código—, así que tomar el nombre de
     * ahí daba {@code null}. Y un {@code null} ahí no fallaba en el dominio: llegaba hasta el
     * {@code NOT NULL} de la V15, dentro del bucle del notificador, que reintentaba cada cinco segundos
     * sin abrir ni una declaración. Por eso el {@code Coding} de este test va deliberadamente pelado.
     */
    @Test
    @DisplayName("el nombre de la enfermedad se lee de su concepto, no del `display` de quien la apunta")
    void elNombreSaleDeLaEnfermedadYNoDeLaPrueba() {
        oido.contesta(
                "$lookup",
                conPropiedades(
                        propiedad(
                                "enfermedad-edo",
                                new org.hl7.fhir.r5.model.Coding(
                                        "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/enfermedades-edo",
                                        "LEGIONELOSIS",
                                        null)),
                        propiedad(
                                "resultado-que-declara",
                                new org.hl7.fhir.r5.model.Coding(
                                        "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/resultados-cualitativos",
                                        "POS",
                                        null)),
                        propiedad(
                                "modalidad-declaracion",
                                new org.hl7.fhir.r5.model.Coding(
                                        "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/modalidades-declaracion-edo",
                                        "URGENTE",
                                        null)),
                        propiedad("plazo-horas", new org.hl7.fhir.r5.model.IntegerType(24)),
                        nombre("Legionelosis")));

        assertThat(terminologia.declaracionDe("LEGIOAG"))
                .as("una regla sin nombre no se descarta en el dominio: revienta al guardarla")
                .isPresent()
                .get()
                .extracting(regla -> regla.nombreDeLaEnfermedad())
                .isEqualTo("Legionelosis");
    }

    @Test
    @DisplayName("lo que no se resolvió no se cachea: la respuesta buena posterior tiene que llegar")
    void loNoResueltoNoSeCachea() {
        oido.contesta("$lookup", conParametro("display", ""));
        oido.contesta("$translate", conResultado(false));

        assertThat(terminologia.pruebaDelCatalogo("GLU").hasText()).isFalse();

        oido.contesta("$lookup", conParametro("display", "Glucosa"));
        assertThat(terminologia.pruebaDelCatalogo("GLU").getText()).isEqualTo("Glucosa");
    }

    private static IGenericClient clienteContra(HttpServer servidor) {
        R5.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        R5.getRestfulClientFactory().setConnectTimeout(2000);
        R5.getRestfulClientFactory().setSocketTimeout(2000);
        return R5.newRestfulGenericClient(
                "http://localhost:" + servidor.getAddress().getPort() + "/fhir");
    }

    private static Parameters conParametro(String nombre, String valor) {
        Parameters salida = new Parameters();
        salida.addParameter(nombre, new org.hl7.fhir.r5.model.StringType(valor));
        return salida;
    }

    /**
     * Una propiedad de concepto con la forma con la que llega de verdad: un parámetro
     * {@code property} con las partes {@code code} y {@code value}, no un parámetro con el nombre de
     * la propiedad. Es la confusión que haría que el cliente leyera vacío sin dar ningún error.
     */
    private static Parameters.ParametersParameterComponent propiedad(
            String codigo, org.hl7.fhir.r5.model.DataType valor) {
        Parameters.ParametersParameterComponent propiedad = new Parameters.ParametersParameterComponent();
        propiedad.setName("property");
        propiedad.addPart().setName("code").setValue(new org.hl7.fhir.r5.model.CodeType(codigo));
        propiedad.addPart().setName("value").setValue(valor);
        return propiedad;
    }

    private static Parameters conPropiedades(Parameters.ParametersParameterComponent... propiedades) {
        Parameters salida = new Parameters();
        for (Parameters.ParametersParameterComponent propiedad : propiedades) {
            salida.addParameter(propiedad);
        }
        return salida;
    }

    /** El {@code display} del propio concepto, que es un parámetro suelto y no una propiedad. */
    private static Parameters.ParametersParameterComponent nombre(String valor) {
        return new Parameters.ParametersParameterComponent()
                .setName("display")
                .setValue(new org.hl7.fhir.r5.model.StringType(valor));
    }

    private static Parameters conResultado(boolean resultado) {
        Parameters salida = new Parameters();
        salida.addParameter("result", resultado);
        return salida;
    }

    private static Parameters traducido(String codigo, String nombre, String relacion) {
        Parameters salida = new Parameters();
        salida.addParameter("result", true);
        Parameters.ParametersParameterComponent emparejamiento =
                salida.addParameter().setName("match");
        emparejamiento.addPart().setName("relationship").setValue(new org.hl7.fhir.r5.model.CodeType(relacion));
        emparejamiento.addPart().setName("concept").setValue(new org.hl7.fhir.r5.model.Coding(LOINC, codigo, nombre));
        return salida;
    }

    private static List<String> nombresDe(Parameters entrada) {
        return entrada.getParameter().stream()
                .map(Parameters.ParametersParameterComponent::getName)
                .toList();
    }

    private static String valorDe(Parameters entrada, String nombre) {
        return entrada.getParameter().stream()
                .filter(parametro -> nombre.equals(parametro.getName()))
                .map(parametro -> parametro.getValue().primitiveValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no se mandó el parámetro «%s»".formatted(nombre)));
    }

    /** Apunta la entrada de cada operación y devuelve la salida que se le haya preparado. */
    private static final class OidoEnElPuerto implements com.sun.net.httpserver.HttpHandler {

        private final Map<String, Parameters> respuestas = new ConcurrentHashMap<>();
        private final List<String> operaciones = new ArrayList<>();
        private final Map<String, Parameters> entradas = new ConcurrentHashMap<>();

        void contesta(String operacion, Parameters salida) {
            respuestas.put(operacion, salida);
        }

        Parameters loQueLlegoA(String operacion) {
            assertThat(operaciones)
                    .as("no se llamó a %s; llamadas: %s", operacion, operaciones)
                    .contains(operacion);
            return entradas.get(operacion);
        }

        @Override
        public void handle(HttpExchange intercambio) throws IOException {
            String operacion = operacionDe(intercambio.getRequestURI());
            String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (operaciones) {
                operaciones.add(operacion);
            }
            entradas.put(operacion, R5.newJsonParser().parseResource(Parameters.class, cuerpo));

            Parameters salida = respuestas.get(operacion);
            byte[] respuesta = R5.newJsonParser()
                    .encodeResourceToString(salida != null ? salida : new Parameters())
                    .getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/fhir+json;charset=UTF-8");
            intercambio.sendResponseHeaders(200, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        }

        private static String operacionDe(URI uri) {
            String ruta = uri.getPath();
            return ruta.substring(ruta.lastIndexOf('/') + 1);
        }
    }

    static {
        // Si el parser de HAPI no está inicializado, el primer test paga el arranque del contexto R5
        // dentro del tiempo de espera del cliente y falla por un motivo que no es el suyo.
        try {
            R5.newJsonParser().encodeResourceToString(new Parameters());
        } catch (RuntimeException e) {
            throw new UncheckedIOException(new IOException("No se pudo preparar el contexto R5", e));
        }
    }
}
