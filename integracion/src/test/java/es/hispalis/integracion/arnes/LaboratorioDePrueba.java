package es.hispalis.integracion.arnes;

import ca.uhn.fhir.context.FhirContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Patient;

/**
 * Un laboratorio de mentira que habla FHIR de verdad, para probar el canal de extremo a extremo.
 *
 * <p>No es un doble de test del cliente: es un <strong>servidor HTTP real</strong> que recibe lo que
 * el motor envía por el cable. Lo que se comprueba con él es lo que de verdad importa aquí —qué JSON
 * sale del canal y cuántas veces sale—, y eso un simulacro del cliente no lo demuestra: pasaría igual
 * si el motor serializara mal.
 *
 * <p>Lo que <strong>no</strong> hace: validar invariantes de negocio. Para eso está el backend, y
 * probarlo aquí sería probar dos veces lo mismo con la mitad de rigor. Aquí solo se guarda lo que
 * llega y se cuenta.
 */
public final class LaboratorioDePrueba implements AutoCloseable {

    private final FhirContext contexto = FhirContext.forR5();
    private final HttpServer servidor;
    private final Map<String, Patient> porNhc = new ConcurrentHashMap<>();
    private final List<Patient> altas = Collections.synchronizedList(new ArrayList<>());
    private final List<Patient> correcciones = Collections.synchronizedList(new ArrayList<>());

    private LaboratorioDePrueba(HttpServer servidor) {
        this.servidor = servidor;
    }

    /** Arranca en un puerto libre. */
    public static LaboratorioDePrueba arrancado() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LaboratorioDePrueba laboratorio = new LaboratorioDePrueba(servidor);
            servidor.createContext("/fhir/Patient", laboratorio::atender);
            servidor.start();
            return laboratorio;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo arrancar el laboratorio de prueba", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/fhir";
    }

    /** Los pacientes dados de alta, en orden. */
    public List<Patient> altas() {
        return List.copyOf(altas);
    }

    /** Las filiaciones corregidas, en orden. */
    public List<Patient> correcciones() {
        return List.copyOf(correcciones);
    }

    /** Cuántas veces se escribió, sumando altas y correcciones. */
    public int escrituras() {
        return altas.size() + correcciones.size();
    }

    public void olvidarTodo() {
        porNhc.clear();
        altas.clear();
        correcciones.clear();
    }

    @Override
    public void close() {
        servidor.stop(0);
    }

    private void atender(HttpExchange intercambio) throws IOException {
        try {
            String ruta = intercambio.getRequestURI().getPath();
            String metodo = intercambio.getRequestMethod();

            if ("POST".equals(metodo) && ruta.endsWith("/_search")) {
                buscar(intercambio);
            } else if ("POST".equals(metodo)) {
                darDeAlta(intercambio);
            } else if ("PUT".equals(metodo)) {
                corregir(intercambio);
            } else {
                intercambio.sendResponseHeaders(405, -1);
            }
        } finally {
            intercambio.close();
        }
    }

    private void buscar(HttpExchange intercambio) throws IOException {
        String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Optional<Patient> encontrado = nhcBuscado(cuerpo).map(porNhc::get).filter(java.util.Objects::nonNull);

        Bundle resultado = new Bundle().setType(Bundle.BundleType.SEARCHSET);
        encontrado.ifPresent(paciente -> resultado.addEntry().setResource(paciente));
        resultado.setTotal(encontrado.isPresent() ? 1 : 0);
        responder(intercambio, 200, resultado, null);
    }

    private void darDeAlta(HttpExchange intercambio) throws IOException {
        Patient recibido = leer(intercambio);
        String id = UUID.randomUUID().toString();
        recibido.setId(id);
        nhcDe(recibido).ifPresent(nhc -> porNhc.put(nhc, recibido));
        altas.add(recibido);
        responder(intercambio, 201, recibido, "%s/Patient/%s/_history/1".formatted(url(), id));
    }

    private void corregir(HttpExchange intercambio) throws IOException {
        Patient recibido = leer(intercambio);
        nhcDe(recibido).ifPresent(nhc -> porNhc.put(nhc, recibido));
        correcciones.add(recibido);
        responder(intercambio, 200, recibido, null);
    }

    private Patient leer(HttpExchange intercambio) throws IOException {
        // UTF-8 explícito: el JSON de FHIR lo es por definición, y aquí es donde se comprobaría si
        // el motor hubiera perdido la Ñ por el camino.
        String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return contexto.newJsonParser().parseResource(Patient.class, cuerpo);
    }

    private void responder(
            HttpExchange intercambio, int codigo, org.hl7.fhir.r5.model.Resource recurso, String location)
            throws IOException {
        byte[] cuerpo = contexto.newJsonParser().encodeResourceToString(recurso).getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/fhir+json;charset=utf-8");
        if (location != null) {
            intercambio.getResponseHeaders().set("Location", location);
        }
        intercambio.sendResponseHeaders(codigo, cuerpo.length);
        intercambio.getResponseBody().write(cuerpo);
    }

    /** Del cuerpo {@code identifier=<system>|<valor>} de un {@code POST …/_search}. */
    private static Optional<String> nhcBuscado(String cuerpoDelFormulario) {
        for (String pareja : cuerpoDelFormulario.split("&")) {
            String[] partes = pareja.split("=", 2);
            if (partes.length == 2 && "identifier".equals(partes[0])) {
                String valor = URLDecoder.decode(partes[1], StandardCharsets.UTF_8);
                if (valor.startsWith(SistemasDeIdentificador.NHC + "|")) {
                    return Optional.of(valor.substring(valor.indexOf('|') + 1));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> nhcDe(Patient paciente) {
        return paciente.getIdentifier().stream()
                .filter(identificador -> SistemasDeIdentificador.NHC.equals(identificador.getSystem()))
                .map(org.hl7.fhir.r5.model.Identifier::getValue)
                .findFirst();
    }
}
