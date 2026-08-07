package es.hispalis.integracion.arnes;

import ca.uhn.fhir.context.FhirContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.StringType;
import org.hl7.fhir.r5.model.ValueSet;

/**
 * Un servidor de terminología de mentira, cargado con la terminología de verdad.
 *
 * <p>Responde las cuatro operaciones —{@code $lookup}, {@code $validate-code}, {@code $translate} y
 * {@code $expand}— <strong>por HTTP</strong>, y las contesta a partir del {@code CodeSystem}, el
 * {@code ConceptMap} y los {@code ValueSet} que produce SUSHI. El motor no distingue esto del HAPI
 * del {@code compose}: habla el mismo protocolo contra otra URL, que es justamente lo que D14 dice
 * que tiene que poder pasar.
 *
 * <p><strong>Aquí es donde vive la lectura de ficheros, y aquí es donde debe vivir.</strong> Antes la
 * hacía el motor y por eso tenía que saber que la unidad de una prueba está en una propiedad del
 * {@code CodeSystem}; ahora eso lo sabe el servidor, que es de quien es. El motor pregunta.
 *
 * <p>Las respuestas imitan lo que devuelve HAPI 8.10, medido contra el contenedor: la vuelta del
 * {@code $translate} se pide con {@code reverse=true} y contesta {@code equivalence} —el nombre de
 * R4—, y la unidad UCUM llega como {@code Coding} dentro de una parte {@code property}. Si algún día
 * el servidor real cambiara esas formas, esto dejaría de parecerse a él: por eso las cuatro
 * operaciones se ejercitan también contra el contenedor, y no solo aquí.
 */
public final class TerminologiaDePrueba implements AutoCloseable {

    public static final String CATALOGO = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas";
    public static final String LOINC = "http://loinc.org";

    private static final String FICHERO_CODESYSTEM = "CodeSystem-catalogo-pruebas.json";
    private static final String FICHERO_CONCEPTMAP = "ConceptMap-catalogo-a-loinc.json";

    /** Los {@code ValueSet} de la guía que este servidor sirve, por su nombre de fichero. */
    private static final Set<String> CONJUNTOS =
            Set.of("ValueSet-pruebas-del-catalogo.json", "ValueSet-tipos-muestra.json", "ValueSet-catalogo-edo.json");

    private static final String PROPIEDAD_UNIDAD = "unidad-ucum";

    private final FhirContext contexto = FhirContext.forR5();
    private final HttpServer servidor;

    private final CodeSystem catalogo;
    private final ConceptMap mapa;
    private final Map<String, ValueSet> conjuntos;

    private TerminologiaDePrueba(
            HttpServer servidor, CodeSystem catalogo, ConceptMap mapa, Map<String, ValueSet> conjuntos) {
        this.servidor = servidor;
        this.catalogo = catalogo;
        this.mapa = mapa;
        this.conjuntos = conjuntos;
    }

    /** Arranca en un puerto libre, cargado con lo que haya en el directorio de la guía. */
    public static TerminologiaDePrueba arrancada() {
        Path directorio = directorioDeLaGuia();
        FhirContext contexto = FhirContext.forR5();
        try {
            CodeSystem catalogo = leer(contexto, directorio.resolve(FICHERO_CODESYSTEM), CodeSystem.class);
            ConceptMap mapa = leer(contexto, directorio.resolve(FICHERO_CONCEPTMAP), ConceptMap.class);

            Map<String, ValueSet> conjuntos = new LinkedHashMap<>();
            for (String fichero : CONJUNTOS) {
                ValueSet conjunto = leer(contexto, directorio.resolve(fichero), ValueSet.class);
                conjuntos.put(conjunto.getUrl(), conjunto);
            }

            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TerminologiaDePrueba terminologia = new TerminologiaDePrueba(servidor, catalogo, mapa, conjuntos);
            servidor.createContext("/fhir", terminologia::atender);
            servidor.start();
            return terminologia;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo arrancar el servidor de terminología de prueba", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/fhir";
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    private void atender(HttpExchange intercambio) throws IOException {
        String ruta = intercambio.getRequestURI().getPath();
        String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Parameters entrada =
                cuerpo.isBlank() ? new Parameters() : contexto.newJsonParser().parseResource(Parameters.class, cuerpo);

        Resource salida =
                switch (ruta.substring(ruta.lastIndexOf('/') + 1)) {
                    case "$lookup" -> lookup(entrada);
                    case "$validate-code" -> validateCode(entrada);
                    case "$translate" -> translate(entrada);
                    case "$expand" -> expand(entrada);
                    default -> error("Este servidor de prueba no implementa " + ruta);
                };

        byte[] respuesta =
                contexto.newJsonParser().encodeResourceToString(salida).getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "application/fhir+json;charset=UTF-8");
        intercambio.sendResponseHeaders(salida instanceof OperationOutcome ? 400 : 200, respuesta.length);
        intercambio.getResponseBody().write(respuesta);
        intercambio.close();
    }

    private Resource lookup(Parameters entrada) {
        String system = valor(entrada, "system");
        String codigo = valor(entrada, "code");
        if (!CATALOGO.equals(system)) {
            return error("Este servidor de prueba solo tiene cargado el catálogo del laboratorio.");
        }
        Optional<CodeSystem.ConceptDefinitionComponent> concepto = catalogo.getConcept().stream()
                .filter(candidato -> candidato.getCode().equals(codigo))
                .findFirst();
        if (concepto.isEmpty()) {
            return error("No se conoce el código «%s» en «%s».".formatted(codigo, system));
        }

        Parameters salida = new Parameters();
        salida.addParameter("name", new StringType(catalogo.getName()));
        salida.addParameter("display", new StringType(concepto.get().getDisplay()));
        unidadDe(concepto.get()).ifPresent(unidad -> {
            Parameters.ParametersParameterComponent propiedad =
                    salida.addParameter().setName("property");
            propiedad.addPart().setName("code").setValue(new CodeType(PROPIEDAD_UNIDAD));
            propiedad.addPart().setName("value").setValue(new Coding("http://unitsofmeasure.org", unidad, null));
        });
        return salida;
    }

    private Resource validateCode(Parameters entrada) {
        ValueSet conjunto = conjuntos.get(valor(entrada, "url"));
        if (conjunto == null) {
            return error("No hay ningún ValueSet cargado con la URL «%s».".formatted(valor(entrada, "url")));
        }
        String codigo = valor(entrada, "code");

        Parameters salida = new Parameters();
        salida.addParameter("result", contiene(conjunto, valor(entrada, "system"), codigo));
        return salida;
    }

    /**
     * {@code $translate}, en las dos direcciones.
     *
     * <p>La vuelta se pide con {@code reverse=true}. Que este servidor <strong>no</strong> entienda el
     * {@code targetCode} de R5 no es un descuido: es exactamente lo que hace HAPI 8.10, y si aquí
     * funcionara, el cliente pasaría los tests con un camino que en el {@code compose} no se recorre.
     */
    private Resource translate(Parameters entrada) {
        if (valor(entrada, "targetCode") != null || valor(entrada, "targetCoding") != null) {
            return error("HAPI-1154: One (and only one) of the in parameters (code, coding, codeableConcept) must "
                    + "be provided, to identify the code that is to be translated.");
        }
        boolean alReves = Boolean.parseBoolean(valor(entrada, "reverse"));
        String codigo = alReves ? valor(entrada, "code") : valor(entrada, "sourceCode");

        Optional<Coding> encontrado = alReves ? origenDe(codigo) : destinoDe(codigo);
        Parameters salida = new Parameters();
        salida.addParameter("result", encontrado.isPresent());
        encontrado.ifPresent(concepto -> {
            Parameters.ParametersParameterComponent match =
                    salida.addParameter().setName("match");
            // `equivalence`, con el nombre de R4: es lo que devuelve HAPI 8.10.
            match.addPart().setName("equivalence").setValue(new CodeType(relacionDe(codigo, alReves)));
            match.addPart().setName("concept").setValue(concepto);
        });
        return salida;
    }

    private Resource expand(Parameters entrada) {
        ValueSet conjunto = conjuntos.get(valor(entrada, "url"));
        if (conjunto == null) {
            return error("No hay ningún ValueSet cargado con la URL «%s».".formatted(valor(entrada, "url")));
        }
        ValueSet expandido = conjunto.copy();
        expandido.getExpansion().setTotal(codigosDe(conjunto).size());
        return expandido;
    }

    private boolean contiene(ValueSet conjunto, String system, String codigo) {
        return codigosDe(conjunto).contains(codigo)
                && conjunto.getCompose().getInclude().stream()
                        .anyMatch(inclusion -> inclusion.getSystem().equals(system));
    }

    /**
     * Los códigos de un conjunto: los enumerados, o todo el catálogo si se incluye entero.
     *
     * <p>Los dos casos hacen falta porque la guía usa los dos: {@code tipos-muestra} enumera y
     * {@code pruebas-del-catalogo} incluye el {@code CodeSystem} completo.
     */
    private Set<String> codigosDe(ValueSet conjunto) {
        return conjunto.getCompose().getInclude().stream()
                .flatMap(inclusion -> inclusion.hasConcept()
                        ? inclusion.getConcept().stream().map(ValueSet.ConceptReferenceComponent::getCode)
                        : codigosDelCodeSystem(inclusion.getSystem()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> codigosDelCodeSystem(String system) {
        if (!CATALOGO.equals(system)) {
            return Set.of();
        }
        return catalogo.getConcept().stream()
                .map(CodeSystem.ConceptDefinitionComponent::getCode)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Optional<Coding> destinoDe(String codigoLocal) {
        return recorrer().entrySet().stream()
                .filter(mapeo -> mapeo.getKey().equals(codigoLocal))
                .map(mapeo -> new Coding(
                        LOINC, mapeo.getValue().getCode(), mapeo.getValue().getDisplay()))
                .findFirst();
    }

    private Optional<Coding> origenDe(String loinc) {
        return recorrer().entrySet().stream()
                .filter(mapeo -> mapeo.getValue().getCode().equals(loinc))
                .map(mapeo -> new Coding(CATALOGO, mapeo.getKey(), displayDe(mapeo.getKey())))
                .findFirst();
    }

    private String relacionDe(String codigo, boolean alReves) {
        Optional<ConceptMap.TargetElementComponent> destino = recorrer().entrySet().stream()
                .filter(mapeo -> alReves
                        ? mapeo.getValue().getCode().equals(codigo)
                        : mapeo.getKey().equals(codigo))
                .map(Map.Entry::getValue)
                .findFirst();
        boolean equivalente = destino.map(
                        elemento -> elemento.getRelationship() == Enumerations.ConceptMapRelationship.EQUIVALENT)
                .orElse(false);
        // HAPI traduce las relaciones de R5 a los códigos de R4 al contestar; `narrower` es el que
        // devuelve para `source-is-broader-than-target`.
        return equivalente ? "equivalent" : "narrower";
    }

    private Map<String, ConceptMap.TargetElementComponent> recorrer() {
        Map<String, ConceptMap.TargetElementComponent> mapeos = new HashMap<>();
        for (ConceptMap.ConceptMapGroupComponent grupo : mapa.getGroup()) {
            if (!LOINC.equals(grupo.getTarget())) {
                continue;
            }
            for (ConceptMap.SourceElementComponent elemento : grupo.getElement()) {
                elemento.getTarget().stream().findFirst().ifPresent(destino -> mapeos.put(elemento.getCode(), destino));
            }
        }
        return mapeos;
    }

    private String displayDe(String codigoLocal) {
        return catalogo.getConcept().stream()
                .filter(concepto -> concepto.getCode().equals(codigoLocal))
                .map(CodeSystem.ConceptDefinitionComponent::getDisplay)
                .findFirst()
                .orElse(null);
    }

    private static Optional<String> unidadDe(CodeSystem.ConceptDefinitionComponent concepto) {
        return concepto.getProperty().stream()
                .filter(propiedad -> PROPIEDAD_UNIDAD.equals(propiedad.getCode()))
                .map(CodeSystem.ConceptPropertyComponent::getValue)
                .filter(Coding.class::isInstance)
                .map(valor -> ((Coding) valor).getCode())
                .findFirst();
    }

    private static String valor(Parameters entrada, String nombre) {
        return entrada.getParameter().stream()
                .filter(parametro -> nombre.equals(parametro.getName()) && parametro.getValue() != null)
                .map(parametro -> parametro.getValue().primitiveValue())
                .findFirst()
                .orElse(null);
    }

    private static OperationOutcome error(String diagnostico) {
        OperationOutcome problema = new OperationOutcome();
        problema.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.PROCESSING)
                .setDiagnostics(diagnostico);
        return problema;
    }

    private static Path directorioDeLaGuia() {
        String indicado = System.getenv("HISPALIS_GUIA");
        return indicado != null && !indicado.isBlank()
                ? Path.of(indicado)
                : Path.of("..", "ig", "fsh-generated", "resources");
    }

    private static <T extends Resource> T leer(FhirContext contexto, Path fichero, Class<T> tipo) throws IOException {
        if (!Files.exists(fichero)) {
            throw new IOException(("No se encuentra «%s». El servidor de terminología de los tests se carga con lo "
                            + "que produce la guía: ejecuta «npx fsh-sushi .» dentro de «ig/», o apunta a otro "
                            + "directorio con HISPALIS_GUIA.")
                    .formatted(fichero.toAbsolutePath()));
        }
        return contexto.newJsonParser().parseResource(tipo, Files.readString(fichero, StandardCharsets.UTF_8));
    }
}
