package es.hispalis.backend.fhir.resultado;

import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.dominio.resultado.Validacion;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.stereotype.Component;

/**
 * La procedencia de un resultado validado: quién lo firmó y cuándo.
 *
 * <p>Va en un {@code Provenance} y no en una extensión porque §6.1 del diseño ya lo verificó: FHIR
 * tiene recurso para esto. Un {@code Observation} dice <em>qué</em> se midió; quién responde de que
 * esa medida sea publicable es un hecho sobre el recurso, no un campo suyo.
 */
@Component
public class TraductorDeProcedencia {

    private static final String TIPO_DE_AGENTE = "http://terminology.hl7.org/CodeSystem/provenance-participant-type";

    /** Genera la procedencia publicable de un resultado ya validado. */
    public Provenance aFhir(Resultado resultado) {
        Validacion validacion = resultado
                .validacion()
                .orElseThrow(() -> new IllegalStateException(
                        "No se publica la procedencia de un resultado sin validar: " + resultado.id()));

        Provenance procedencia = new Provenance();
        procedencia.setId(identidadDe(resultado).toString());
        procedencia.addTarget(new Reference("Observation/" + resultado.id()));
        procedencia.setRecorded(Date.from(validacion.realizadaEn()));
        procedencia
                .addAgent()
                .setType(new CodeableConcept()
                        .addCoding(new Coding().setSystem(TIPO_DE_AGENTE).setCode("verifier")))
                .setWho(new Reference(validacion.facultativo()));
        return procedencia;
    }

    /**
     * El id lógico de la procedencia, <strong>derivado del resultado</strong> en vez de sorteado.
     *
     * <p>No es una floritura: el reconciliador del hito 2 tiene que poder regenerar la proyección
     * entera desde el dominio, y con un identificador aleatorio cada pasada crearía otro
     * {@code Provenance} para la misma firma. Al derivarlo, regenerar sobrescribe en vez de duplicar
     * — que es lo que significa que el reconciliador sea idempotente.
     *
     * <p>Un resultado tiene como mucho una validación (revalidar está prohibido), así que la
     * correspondencia es uno a uno y no hace falta más entropía que su identidad.
     */
    public static UUID identidadDe(Resultado resultado) {
        return UUID.nameUUIDFromBytes(
                ("hispalis:procedencia:validacion:" + resultado.id()).getBytes(StandardCharsets.UTF_8));
    }
}
