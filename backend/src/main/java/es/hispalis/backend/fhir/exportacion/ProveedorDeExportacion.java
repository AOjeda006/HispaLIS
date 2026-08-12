package es.hispalis.backend.fhir.exportacion;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.aplicacion.exportacion.CerrarExportacion;
import es.hispalis.backend.aplicacion.exportacion.LanzarExportacion;
import es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros;
import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion;
import es.hispalis.backend.fhir.seguridad.QuienLlama;
import es.hispalis.backend.fhir.seguridad.Testigo;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hl7.fhir.r5.model.Group;
import org.hl7.fhir.r5.model.IdType;

/**
 * Bulk Data: {@code $export} sobre una cohorte, su sondeo y la descarga de los NDJSON.
 *
 * <p>Va como <strong>proveedor suelto</strong> y no como {@code ProveedorPropio}: sustituir el
 * proveedor de {@code Group} desde aquí mezclaría dos cosas distintas —quién publica el recurso y
 * quién lo exporta—. De cerrar la escritura del {@code Group} se encarga {@code ProveedorDeCohorte}.
 *
 * <h2>Los tres puntos y por qué son tres</h2>
 *
 * <pre>
 * POST   [base]/Group/{id}/$export     → 202 + Content-Location
 * GET    [base]/$export-estado?…       → 202 mientras trabaja · 200 + manifiesto · 404 si ya no hay
 * DELETE [base]/$export-estado?…       → 202, y a partir de ahí 404
 * GET    [base]/$export-fichero?…      → el NDJSON, con Expires
 * </pre>
 *
 * <p>El estándar quiere que el sondeo y el lanzamiento sean recursos distintos, y con razón: el
 * lanzamiento contesta en milisegundos y el trabajo puede tardar minutos. Lo que sí es propio de esta
 * guía es <strong>que la descarga vaya por un billete opaco</strong> en vez de por el nombre del
 * fichero: una URL viaja al log del proxy, al historial y a la analítica, así que no puede decir de
 * quién son los datos ni de qué enfermedad va la cohorte (adr-0016).
 *
 * <h2>Un parámetro que no se soporta se RECHAZA</h2>
 *
 * <p>Y esto es lo contrario de la búsqueda normal, donde FHIR permite ignorar en silencio lo que no se
 * entiende. En Bulk Data, ignorar {@code _since} devolvería la cohorte entera a quien pidió solo lo
 * nuevo —más datos de los que pidió, sin decírselo—, así que se contesta {@code 400} con un
 * {@code OperationOutcome} que dice cuál sobra.
 */
public class ProveedorDeExportacion {

    /** Los formatos de salida que se aceptan. NDJSON es obligatorio en el estándar; el resto, extra. */
    private static final Set<String> FORMATOS = Set.of("application/fhir+ndjson", "application/ndjson", "ndjson");

    /** Lo que el cliente puede mandar. Cualquier otra cosa es un `400` con su nombre dentro. */
    private static final Set<String> PARAMETROS_ACEPTADOS = Set.of("_outputFormat", "_type");

    private static final DateTimeFormatter HTTP = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final LanzarExportacion lanzar;
    private final CerrarExportacion cerrar;
    private final RepositorioDeExportaciones trabajos;
    private final AlmacenDeFicheros almacen;
    private final DaoRegistry daos;
    private final QuienLlama quienLlama;
    private final ObjectMapper json;

    public ProveedorDeExportacion(
            LanzarExportacion lanzar,
            CerrarExportacion cerrar,
            RepositorioDeExportaciones trabajos,
            AlmacenDeFicheros almacen,
            DaoRegistry daos,
            QuienLlama quienLlama,
            ObjectMapper json) {
        this.lanzar = lanzar;
        this.cerrar = cerrar;
        this.trabajos = trabajos;
        this.almacen = almacen;
        this.daos = daos;
        this.quienLlama = quienLlama;
        this.json = json;
    }

    // ─── Lanzamiento ────────────────────────────────────────────────────────

    /**
     * {@code POST|GET [base]/Group/{id}/$export}.
     *
     * <p>Contesta {@code 202} y una URL de sondeo, siempre. No hay modo síncrono ni para una cohorte de
     * dos personas: un cliente que aprendiera a leer la respuesta directa dejaría de funcionar el día
     * que la cohorte creciera, y ese es justo el día en que hace falta.
     */
    @Operation(name = "$export", typeName = "Group", idempotent = true, manualResponse = true)
    public void exportar(
            @IdParam IdType cohorte,
            @OperationParam(name = "_type", max = OperationParam.MAX_UNLIMITED)
                    List<org.hl7.fhir.r5.model.StringType> tipos,
            RequestDetails peticion,
            HttpServletResponse respuesta)
            throws IOException {

        rechazarLoQueNoSeSoporta(peticion);
        exigirFormatoNdjson(peticion);
        exigirQueLaCohorteExista(cohorte);

        TrabajoDeExportacion trabajo =
                lanzar.ejecutar(cohorte.toUnqualifiedVersionless().getValue(), quienPide(), tiposPedidos(tipos));

        respuesta.setStatus(HttpServletResponse.SC_ACCEPTED);
        respuesta.setHeader("Content-Location", urlDelSondeo(peticion, trabajo.id()));
        // Se confirma la preferencia solo si el cliente la pidió, que es lo que dice HTTP. Anunciar
        // `Preference-Applied` sin que nadie haya preferido nada es ruido que otros clientes leen.
        if (pidioAsincrono(peticion)) {
            respuesta.setHeader("Preference-Applied", "respond-async");
        }
        respuesta.getWriter().close();
    }

    // ─── Sondeo y cancelación ───────────────────────────────────────────────

    /**
     * {@code GET|DELETE [base]/$export-estado?_jobId=…}.
     *
     * <p>{@code deleteEnabled} es lo que permite que el mismo punto responda al {@code DELETE} que la
     * IG define para cancelar. Tener dos URL para «mira cómo va» y «ya no lo quiero» obligaría al
     * cliente a guardar dos, y la norma solo le entrega una.
     */
    @Operation(name = "$export-estado", idempotent = true, manualResponse = true, deleteEnabled = true)
    public void estado(
            @OperationParam(name = "_jobId", min = 1, max = 1) org.hl7.fhir.r5.model.StringType trabajoId,
            RequestDetails peticion,
            HttpServletResponse respuesta)
            throws IOException {

        UUID id = identidadDe(trabajoId);
        TrabajoDeExportacion trabajo = trabajos.buscar(id).orElseThrow(() -> yaNoHay(id));
        exigirQueSeaSuya(trabajo);

        if (peticion.getRequestType() == RequestTypeEnum.DELETE) {
            cerrar.ejecutar(id, "el cliente la ha cancelado");
            respuesta.setStatus(HttpServletResponse.SC_ACCEPTED);
            respuesta.getWriter().close();
            return;
        }

        switch (trabajo.estado()) {
            case EN_CURSO -> {
                respuesta.setStatus(HttpServletResponse.SC_ACCEPTED);
                // Texto libre y de menos de cien caracteres, como manda la norma. Un cliente NO debe
                // construir lógica sobre esto, y por eso no lleva números que inviten a parsearlo.
                respuesta.setHeader("X-Progress", "Montando la exportación de " + trabajo.cohorte());
                respuesta.setHeader("Retry-After", "1");
                respuesta.getWriter().close();
            }
            case TERMINADA -> escribirElManifiesto(trabajo, peticion, respuesta);
            case FALLIDA ->
                throw new InvalidRequestException("La exportación " + id + " falló: "
                        + trabajo.motivoDelFallo().orElse("sin motivo registrado"));
            case CERRADA -> throw yaNoHay(id);
        }
    }

    // ─── Descarga ───────────────────────────────────────────────────────────

    /**
     * {@code GET [base]/$export-fichero?_billete=…}.
     *
     * <p>El billete es lo único que identifica al fichero, y no dice nada: ni la cohorte, ni el tipo,
     * ni desde luego el paciente. Es la diferencia entre una URL que se puede pegar en un correo y una
     * que además cuenta algo al pegarla.
     */
    @Operation(name = "$export-fichero", idempotent = true, manualResponse = true)
    public void fichero(
            @OperationParam(name = "_billete", min = 1, max = 1) org.hl7.fhir.r5.model.StringType billete,
            HttpServletResponse respuesta)
            throws IOException {

        String buscado = billete == null ? "" : billete.getValueNotNull();
        TrabajoDeExportacion trabajo = trabajos.vivas().stream()
                .map(trabajos::buscar)
                .flatMap(Optional::stream)
                .filter(candidato -> candidato.ficheroDelBillete(buscado).isPresent())
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Ese fichero de exportación no existe o ya ha caducado. Vuelve a pedir el manifiesto."));
        exigirQueSeaSuya(trabajo);

        TrabajoDeExportacion.Fichero fichero =
                trabajo.ficheroDelBillete(buscado).orElseThrow();

        respuesta.setStatus(HttpServletResponse.SC_OK);
        respuesta.setContentType("application/fhir+ndjson");
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        trabajo.caducaEn().ifPresent(cuando -> respuesta.setHeader("Expires", HTTP.format(cuando)));

        try (InputStream ndjson = almacen.abrir(trabajo.id(), fichero.nombre())) {
            ndjson.transferTo(respuesta.getOutputStream());
        } catch (IOException seCorto) {
            throw new UncheckedIOException("No se pudo servir el fichero de la exportación " + trabajo.id(), seCorto);
        }
    }

    // ─── El manifiesto ──────────────────────────────────────────────────────

    /**
     * El manifiesto, con los tres arrays que el estándar define.
     *
     * <p>{@code error} y {@code deleted} van <strong>aunque estén vacíos</strong>. No es simetría
     * estética: el éxito parcial es conforme —el trabajo acaba bien y los problemas se cuentan en
     * {@code error}— y un cliente que no encuentre el array puede concluir que no hay nada que leer
     * cuando lo que pasa es que el servidor no lo publica.
     */
    private void escribirElManifiesto(
            TrabajoDeExportacion trabajo, RequestDetails peticion, HttpServletResponse respuesta) throws IOException {

        Map<String, Object> manifiesto = new LinkedHashMap<>();
        manifiesto.put("transactionTime", trabajo.corte().toString());
        manifiesto.put("request", peticion.getFhirServerBase() + "/" + trabajo.cohorte() + "/$export");
        // Los ficheros los sirve este mismo servidor, detrás de la misma autorización: el token va.
        // Con `false` serían *capability URLs* y mandarlo sería filtrar la credencial a un servidor de
        // ficheros que no la necesita.
        manifiesto.put("requiresAccessToken", true);

        List<Map<String, Object>> salida = new ArrayList<>();
        for (TrabajoDeExportacion.Fichero fichero : trabajo.ficheros()) {
            Map<String, Object> entrada = new LinkedHashMap<>();
            entrada.put("type", fichero.tipoDeRecurso());
            entrada.put("url", urlDeDescarga(peticion, fichero.billete()));
            entrada.put("count", fichero.recursos());
            salida.add(entrada);
        }
        manifiesto.put("output", salida);
        manifiesto.put("deleted", List.of());
        manifiesto.put("error", List.of());

        respuesta.setStatus(HttpServletResponse.SC_OK);
        respuesta.setContentType("application/json");
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        trabajo.caducaEn().ifPresent(cuando -> respuesta.setHeader("Expires", HTTP.format(cuando)));
        respuesta.getWriter().write(json.writeValueAsString(manifiesto));
        respuesta.getWriter().close();
    }

    // ─── Comprobaciones ─────────────────────────────────────────────────────

    private static void rechazarLoQueNoSeSoporta(RequestDetails peticion) {
        String sobran = peticion.getParameters().keySet().stream()
                .filter(nombre -> !PARAMETROS_ACEPTADOS.contains(nombre))
                .sorted()
                .reduce((uno, otro) -> uno + ", " + otro)
                .orElse("");
        if (!sobran.isEmpty()) {
            throw new InvalidRequestException(
                    ("Esta exportación no soporta %s. No se ignora a propósito: ignorarlo devolvería más datos de "
                                    + "los que has pedido sin decírtelo. Los parámetros admitidos son `_type` y "
                                    + "`_outputFormat`.")
                            .formatted(sobran));
        }
    }

    private static void exigirFormatoNdjson(RequestDetails peticion) {
        String[] pedido = peticion.getParameters().get("_outputFormat");
        if (pedido != null && pedido.length > 0 && !FORMATOS.contains(pedido[0])) {
            throw new InvalidRequestException(
                    "Formato de salida no soportado: " + pedido[0] + ". Esta exportación entrega NDJSON.");
        }
    }

    private void exigirQueLaCohorteExista(IdType cohorte) {
        try {
            daos.getResourceDao(Group.class).read(cohorte, new SystemRequestDetails());
        } catch (ResourceNotFoundException noEsta) {
            throw new ResourceNotFoundException(
                    "No hay ninguna cohorte de vigilancia con ese identificador. Las cohortes las abre el "
                            + "laboratorio al declarar; se descubren con `GET [base]/Group?identifier=…`.");
        }
    }

    /**
     * Una exportación solo la sondea y la descarga quien la pidió.
     *
     * <p>La IG lo deja como opción del servidor y aquí se toma: el identificador de un trabajo viaja en
     * una cabecera y en el log de un proxy, y sin esta comprobación cualquier cliente con permiso de
     * exportar podría descargarse la cohorte que pidió otro. Con la seguridad apagada no hay
     * solicitante y no hay nada que comparar.
     */
    private void exigirQueSeaSuya(TrabajoDeExportacion trabajo) {
        Optional<String> ahora = quienPide();
        if (trabajo.solicitante().isPresent()
                && ahora.isPresent()
                && !trabajo.solicitante().equals(ahora)) {
            throw new ForbiddenOperationException("Esa exportación la pidió otro cliente.");
        }
    }

    private Optional<String> quienPide() {
        return quienLlama.testigo().map(Testigo::sujeto);
    }

    private static UUID identidadDe(org.hl7.fhir.r5.model.StringType trabajoId) {
        try {
            return UUID.fromString(trabajoId.getValueNotNull());
        } catch (IllegalArgumentException noEsUnTrabajo) {
            throw new InvalidRequestException("`_jobId` no es un identificador de exportación.");
        }
    }

    private static ResourceNotFoundException yaNoHay(UUID id) {
        return new ResourceNotFoundException(
                "La exportación " + id + " ya no está: se canceló o se le pasó el plazo de descarga.");
    }

    private static List<String> tiposPedidos(List<org.hl7.fhir.r5.model.StringType> tipos) {
        if (tipos == null || tipos.isEmpty()) {
            return List.of();
        }
        // Se acepta la lista con comas además del parámetro repetido: las dos formas son equivalentes
        // hoy, aunque el estándar señale la primera como candidata a desaparecer.
        return tipos.stream()
                .map(org.hl7.fhir.r5.model.StringType::getValueNotNull)
                .flatMap(valor -> Arrays.stream(valor.split(",")))
                .map(String::trim)
                .filter(valor -> !valor.isEmpty())
                .toList();
    }

    private static boolean pidioAsincrono(RequestDetails peticion) {
        List<String> preferencias = peticion.getHeaders("Prefer");
        return preferencias != null && preferencias.stream().anyMatch(valor -> valor.contains("respond-async"));
    }

    private static String urlDelSondeo(RequestDetails peticion, UUID trabajo) {
        return peticion.getFhirServerBase() + "/$export-estado?_jobId=" + trabajo;
    }

    private static String urlDeDescarga(RequestDetails peticion, String billete) {
        return peticion.getFhirServerBase() + "/$export-fichero?_billete=" + billete;
    }
}
