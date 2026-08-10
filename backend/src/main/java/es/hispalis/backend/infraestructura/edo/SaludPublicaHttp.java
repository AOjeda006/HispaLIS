package es.hispalis.backend.infraestructura.edo;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.aplicacion.edo.DestinatarioDeLaDeclaracion;
import es.hispalis.backend.dominio.edo.NotificacionEdo;
import es.hispalis.backend.dominio.edo.NotificacionEdo.Acuse;
import es.hispalis.backend.dominio.edo.SaludPublica;
import es.hispalis.backend.fhir.edo.TraductorDeNotificacionEdo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * El adaptador que habla con el servicio de declaraciones del SVEA.
 *
 * <h2>Qué se manda: el propio {@code Task}</h2>
 *
 * <p>El contrato real de Redalerta no es público, así que había que elegir formato, y se manda el
 * mismo recurso que la guía publica en vez de inventar uno propietario. Inventarlo habría añadido un
 * mapeo que no se puede validar contra nada y que no enseña nada; con el {@code Task}, lo que sale por
 * el cable es exactamente lo que el perfil describe, y el receptor puede validarlo.
 *
 * <p>⚠️ <strong>Y lo que NO se manda: filiación.</strong> Una declaración EDO de verdad la lleva —Salud
 * Pública tiene que poder localizar al caso para la encuesta epidemiológica—. Aquí van el código de la
 * enfermedad, el plazo y una referencia seudónima al caso, y nada más, porque el destinatario es
 * simulado y este proyecto no manda datos de persona a ningún sistema externo. Queda escrito en la
 * guía, en {@code docs/PLAN.md} y aquí: es la diferencia consciente entre esta simulación y el sistema
 * real, no un descuido que haya que descubrir leyendo el código.
 *
 * <h2>El acuse se lee del cuerpo, y su ausencia también es una respuesta</h2>
 *
 * <p>Un {@code 200} sin número de registro <strong>no</strong> se traduce a «declarado». Es el caso que
 * más fácil se cuela por bueno, porque a nivel de transporte todo ha ido bien.
 */
public class SaludPublicaHttp implements SaludPublica {

    /** Dónde vive el número de registro en la respuesta del servicio simulado. */
    private static final String CAMPO_DEL_REGISTRO = "registro";

    private static final String CAMPO_DEL_MOTIVO = "motivo";

    private final HttpClient http;
    private final FhirContext contexto;
    private final ObjectMapper json;
    private final TraductorDeNotificacionEdo traductor;
    private final DestinatarioDeLaDeclaracion destinatario;
    private final PropiedadesDelSvea propiedades;

    SaludPublicaHttp(
            FhirContext contexto,
            ObjectMapper json,
            TraductorDeNotificacionEdo traductor,
            DestinatarioDeLaDeclaracion destinatario,
            PropiedadesDelSvea propiedades) {
        this.contexto = contexto;
        this.json = json;
        this.traductor = traductor;
        this.destinatario = destinatario;
        this.propiedades = propiedades;
        this.http = HttpClient.newBuilder()
                .connectTimeout(propiedades.tiempoDeEspera())
                // NUNCA. Un `30x` entregaría la declaración a un sitio que el laboratorio no ha
                // autorizado, y esto lleva —aunque sea seudonimizado— un dato de salud.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Respuesta declarar(NotificacionEdo declaracion) {
        String cuerpo = contexto.newJsonParser()
                .encodeResourceToString(traductor.aFhir(declaracion, destinatario.organismo()));

        HttpResponse<String> respuesta;
        try {
            respuesta = http.send(
                    HttpRequest.newBuilder(URI.create(propiedades.destino()))
                            .timeout(propiedades.tiempoDeEspera())
                            .header("Content-Type", "application/fhir+json")
                            .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException noSePudo) {
            return new Respuesta.NoLlego(noSePudo.getMessage());
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            return new Respuesta.NoLlego("Interrumpido mientras se declaraba.");
        }

        return interpretar(respuesta);
    }

    /**
     * Traduce la respuesta HTTP a una de las cuatro que el dominio distingue.
     *
     * <p>Un {@code 4xx} es el destinatario diciendo que no —una respuesta— y un {@code 5xx} es el
     * destinatario sin poder atender —una avería—. La diferencia importa porque lo primero no se
     * reintenta y lo segundo sí: reenviar veinte veces algo que rechazan por el contenido no lo
     * arregla, y dejar de reintentar algo que falló por la red pierde una declaración.
     */
    private Respuesta interpretar(HttpResponse<String> respuesta) {
        int codigo = respuesta.statusCode();
        if (codigo >= 400 && codigo < 500) {
            return new Respuesta.Rechazada(
                    "Salud Pública respondió %d: %s".formatted(codigo, campo(respuesta.body(), CAMPO_DEL_MOTIVO)));
        }
        if (codigo >= 300) {
            return new Respuesta.NoLlego("Salud Pública respondió " + codigo);
        }

        String registro = campo(respuesta.body(), CAMPO_DEL_REGISTRO);
        if (registro == null || registro.isBlank()) {
            return new Respuesta.RecibidaSinRegistro(
                    "Salud Pública respondió %d y no devolvió número de registro, así que la declaración NO consta."
                            .formatted(codigo));
        }
        return new Respuesta.Acusada(new Acuse(propiedades.destino(), registro, Instant.now()));
    }

    /** Un campo del cuerpo, o {@code null} si el cuerpo no es JSON o no lo trae. */
    private String campo(String cuerpo, String nombre) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        try {
            JsonNode leido = json.readTree(cuerpo).get(nombre);
            return leido == null ? null : leido.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException noEsJson) {
            return null;
        }
    }
}
