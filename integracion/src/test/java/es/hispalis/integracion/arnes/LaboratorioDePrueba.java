package es.hispalis.integracion.arnes;

import ca.uhn.fhir.context.FhirContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;

/**
 * Un laboratorio de mentira que habla FHIR de verdad, para probar los canales de extremo a extremo.
 *
 * <p>No es un doble de test del cliente: es un <strong>servidor HTTP real</strong> que recibe lo que
 * el motor envía por el cable. Lo que se comprueba con él es lo que de verdad importa aquí —qué JSON
 * sale de cada canal y cuántas veces sale—, y eso un simulacro del cliente no lo demuestra: pasaría
 * igual si el motor serializara mal.
 *
 * <p>Lo que <strong>no</strong> hace: validar invariantes de negocio. Para eso está el backend, y
 * probarlo aquí sería probarlo dos veces con la mitad de rigor. Aquí solo se guarda lo que llega y se
 * cuenta.
 *
 * <p>Lo que sí hace y es imprescindible: {@link #fallarLaProximaEscrituraDe(String)}. Sin poder
 * provocar un fallo <strong>a mitad</strong> de un {@code OML^O21}, la decisión D22 no se puede
 * probar — y una decisión de atomicidad que no se prueba es una suposición.
 */
public final class LaboratorioDePrueba implements AutoCloseable {

    private final FhirContext contexto = FhirContext.forR5();
    private final HttpServer servidor;

    /** Lo guardado, por tipo y por id. */
    private final Map<String, Map<String, Resource>> almacen = new ConcurrentHashMap<>();

    /** Cada escritura recibida, en orden: {@code POST ServiceRequest}, {@code PUT Patient}… */
    private final List<String> escrituras = Collections.synchronizedList(new ArrayList<>());

    /** El tipo cuya próxima escritura fallará con un 500, o {@code null}. */
    private final AtomicReference<String> saboteado = new AtomicReference<>();

    private LaboratorioDePrueba(HttpServer servidor) {
        this.servidor = servidor;
    }

    /** Arranca en un puerto libre. */
    public static LaboratorioDePrueba arrancado() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LaboratorioDePrueba laboratorio = new LaboratorioDePrueba(servidor);
            for (String tipo : List.of("Patient", "ServiceRequest", "Specimen", "Observation", "DiagnosticReport")) {
                servidor.createContext("/fhir/" + tipo, intercambio -> laboratorio.atender(tipo, intercambio));
            }
            servidor.start();
            return laboratorio;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo arrancar el laboratorio de prueba", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/fhir";
    }

    /** Todo lo guardado de un tipo, en el orden en que se creó. */
    @SuppressWarnings("unchecked")
    public <T extends Resource> List<T> guardados(Class<T> tipo) {
        return List.copyOf((java.util.Collection<T>)
                almacen.getOrDefault(tipo.getSimpleName(), Map.of()).values());
    }

    /** Los pacientes dados de alta, en orden. */
    public List<Patient> altas() {
        return escriturasDe("POST Patient").stream().map(Patient.class::cast).toList();
    }

    /** Las filiaciones corregidas, en orden. */
    public List<Patient> correcciones() {
        return escriturasDe("PUT Patient").stream().map(Patient.class::cast).toList();
    }

    /** Cuántas veces se ha escrito, en total. */
    public int escrituras() {
        return escrituras.size();
    }

    /** Cuántas veces se ha escrito un tipo concreto. Cada entrada es {@code «VERBO Tipo id»}. */
    public long escriturasDe(Class<? extends Resource> tipo) {
        return escrituras.stream()
                .filter(entrada -> entrada.split(" ")[1].equals(tipo.getSimpleName()))
                .count();
    }

    /**
     * Hace que la próxima escritura de ese tipo devuelva {@code 500}.
     *
     * <p>Se desarma sola al dispararse: lo que se quiere provocar es un fallo puntual a mitad de un
     * mensaje, no un laboratorio permanentemente roto — el reproceso tiene que poder completar lo que
     * quedó.
     *
     * @param tipo el nombre del recurso FHIR, p. ej. {@code "Specimen"}
     */
    public void fallarLaProximaEscrituraDe(String tipo) {
        saboteado.set(tipo);
    }

    /** Guarda un recurso sin pasar por HTTP: sirve para preparar el estado de un test. */
    public <T extends Resource> T sembrar(T recurso) {
        if (recurso.getIdElement().getIdPart() == null) {
            recurso.setId(UUID.randomUUID().toString());
        }
        porTipo(recurso.fhirType()).put(recurso.getIdElement().getIdPart(), recurso);
        return recurso;
    }

    public void olvidarTodo() {
        almacen.clear();
        escrituras.clear();
        saboteado.set(null);
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    private List<Resource> escriturasDe(String clave) {
        // El histórico se reconstruye del almacén: cada entrada de `escrituras` guarda el id escrito.
        List<Resource> encontrados = new ArrayList<>();
        synchronized (escrituras) {
            for (String entrada : escrituras) {
                String[] partes = entrada.split(" ");
                if (entrada.startsWith(clave)) {
                    Resource recurso = porTipo(partes[1]).get(partes[2]);
                    if (recurso != null) {
                        encontrados.add(recurso);
                    }
                }
            }
        }
        return encontrados;
    }

    private Map<String, Resource> porTipo(String tipo) {
        return almacen.computeIfAbsent(tipo, sinUsar -> new java.util.LinkedHashMap<>());
    }

    private void atender(String tipo, HttpExchange intercambio) throws IOException {
        try {
            String ruta = intercambio.getRequestURI().getPath();
            String metodo = intercambio.getRequestMethod();

            if ("POST".equals(metodo) && ruta.endsWith("/_search")) {
                buscar(tipo, intercambio);
            } else if ("POST".equals(metodo)) {
                escribir(tipo, intercambio, true);
            } else if ("PUT".equals(metodo)) {
                escribir(tipo, intercambio, false);
            } else if ("GET".equals(metodo)) {
                leer(tipo, ruta, intercambio);
            } else {
                intercambio.sendResponseHeaders(405, -1);
            }
        } finally {
            intercambio.close();
        }
    }

    private void leer(String tipo, String ruta, HttpExchange intercambio) throws IOException {
        String id = ruta.substring(ruta.lastIndexOf('/') + 1);
        Resource encontrado = porTipo(tipo).get(id);
        if (encontrado == null) {
            responder(intercambio, 404, problema("No existe %s/%s".formatted(tipo, id)), null);
            return;
        }
        responder(intercambio, 200, encontrado, null);
    }

    private void escribir(String tipo, HttpExchange intercambio, boolean esAlta) throws IOException {
        // UTF-8 explícito: el JSON de FHIR lo es por definición, y aquí es donde se vería si el motor
        // hubiera perdido la Ñ por el camino.
        String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        // `compareAndSet` y no `getAndSet`: el sabotaje tiene que sobrevivir a las escrituras de OTROS
        // tipos que van antes. Con `getAndSet`, la primera escritura del mensaje —el ServiceRequest—
        // se lo llevaría por delante y el fallo a mitad no llegaría a ocurrir nunca.
        if (saboteado.compareAndSet(tipo, null)) {
            responder(intercambio, 500, problema("Fallo provocado en la escritura de " + tipo), null);
            return;
        }

        Resource recibido = (Resource) contexto.newJsonParser().parseResource(cuerpo);
        String id = esAlta
                ? UUID.randomUUID().toString()
                : intercambio
                        .getRequestURI()
                        .getPath()
                        .substring(intercambio.getRequestURI().getPath().lastIndexOf('/') + 1);
        recibido.setId(id);
        porTipo(tipo).put(id, recibido);
        escrituras.add("%s %s %s".formatted(esAlta ? "POST" : "PUT", tipo, id));

        responder(
                intercambio,
                esAlta ? 201 : 200,
                recibido,
                esAlta ? "%s/%s/%s/_history/1".formatted(url(), tipo, id) : null);
    }

    private void buscar(String tipo, HttpExchange intercambio) throws IOException {
        Map<String, String> criterios =
                criteriosDe(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        List<Resource> encontrados = porTipo(tipo).values().stream()
                .filter(recurso -> cumple(recurso, criterios))
                .toList();

        Bundle resultado = new Bundle().setType(Bundle.BundleType.SEARCHSET);
        encontrados.forEach(recurso -> resultado.addEntry().setResource(recurso));
        resultado.setTotal(encontrados.size());
        responder(intercambio, 200, resultado, null);
    }

    /** Los criterios que los canales usan de verdad. Nada más: un buscador genérico sería otro test. */
    private static boolean cumple(Resource recurso, Map<String, String> criterios) {
        for (Map.Entry<String, String> criterio : criterios.entrySet()) {
            boolean cuadra =
                    switch (criterio.getKey()) {
                        case "identifier" ->
                            recurso instanceof Patient paciente
                                    && criterio.getValue()
                                            .equals(SistemasDeIdentificador.NHC + "|"
                                                    + nhcDe(paciente).orElse(""));
                        case "requisition" ->
                            recurso instanceof ServiceRequest linea
                                    && criterio.getValue()
                                            .equals(linea.getRequisition().getValue());
                        case "accession" ->
                            recurso instanceof Specimen muestra
                                    && criterio.getValue()
                                            .equals(muestra.getAccessionIdentifier()
                                                    .getValue());
                        case "specimen" ->
                            recurso instanceof Observation resultado
                                    && criterio.getValue()
                                            .equals(resultado.getSpecimen().getReference());
                        default -> false;
                    };
            if (!cuadra) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> criteriosDe(String cuerpoDelFormulario) {
        Map<String, String> criterios = new HashMap<>();
        for (String pareja : cuerpoDelFormulario.split("&")) {
            String[] partes = pareja.split("=", 2);
            if (partes.length == 2) {
                criterios.put(partes[0], URLDecoder.decode(partes[1], StandardCharsets.UTF_8));
            }
        }
        return criterios;
    }

    private static OperationOutcome problema(String texto) {
        OperationOutcome problema = new OperationOutcome();
        problema.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.PROCESSING)
                .setDiagnostics(texto);
        return problema;
    }

    private void responder(HttpExchange intercambio, int codigo, Resource recurso, String location) throws IOException {
        byte[] cuerpo = contexto.newJsonParser().encodeResourceToString(recurso).getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/fhir+json;charset=utf-8");
        if (location != null) {
            intercambio.getResponseHeaders().set("Location", location);
        }
        intercambio.sendResponseHeaders(codigo, cuerpo.length);
        intercambio.getResponseBody().write(cuerpo);
    }

    private static Optional<String> nhcDe(Patient paciente) {
        return paciente.getIdentifier().stream()
                .filter(identificador -> SistemasDeIdentificador.NHC.equals(identificador.getSystem()))
                .map(org.hl7.fhir.r5.model.Identifier::getValue)
                .findFirst();
    }

    /** Atajo para los tests: el código del catálogo de un resultado guardado. */
    public static String codigoDe(Observation resultado) {
        return codigoDe(resultado.getCode());
    }

    /** Atajo para los tests: el código del catálogo de una línea guardada. */
    public static String codigoDe(ServiceRequest linea) {
        return codigoDe(linea.getCode().getConcept());
    }

    private static String codigoDe(CodeableConcept concepto) {
        return concepto.getCoding().stream()
                .filter(coding -> CatalogoDelLaboratorio.SYSTEM.equals(coding.getSystem()))
                .map(org.hl7.fhir.r5.model.Coding::getCode)
                .findFirst()
                .orElse(null);
    }
}
