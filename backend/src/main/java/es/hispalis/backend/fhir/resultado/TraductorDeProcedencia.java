package es.hispalis.backend.fhir.resultado;

import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.dominio.resultado.Validacion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.stereotype.Component;

/**
 * La procedencia de un resultado firmado: quién lo firmó y cuándo.
 *
 * <p>Va en un {@code Provenance} y no en una extensión porque §6.1 del diseño ya lo verificó: FHIR
 * tiene recurso para esto. Un {@code Observation} dice <em>qué</em> se midió; quién responde de que
 * esa medida sea publicable es un hecho sobre el recurso, no un campo suyo.
 *
 * <p><strong>Una procedencia por firma, no una por resultado.</strong> Un crítico lleva dos firmas de
 * dos personas distintas, y cada una da fe de un acto propio: fundirlas en un solo {@code Provenance}
 * con dos agentes diría que las dos firmaron a la vez lo mismo, cuando lo que pasó es que una revisó
 * y otra contra-revisó, en momentos distintos. Con dos recursos, cada facultativo responde de lo
 * suyo.
 */
@Component
public class TraductorDeProcedencia {

    private static final String TIPO_DE_AGENTE = "http://terminology.hl7.org/CodeSystem/provenance-participant-type";

    /**
     * Genera las procedencias publicables de un resultado, una por cada firma que tenga.
     *
     * <p>Devuelve vacío si no tiene ninguna, en vez de fallar: un resultado sin firmar es un estado
     * legítimo, y quien recorra la proyección —el reconciliador— no tiene por qué comprobarlo antes.
     */
    public List<Provenance> aFhir(Resultado resultado) {
        List<Validacion> firmas = resultado.firmas();
        List<Provenance> procedencias = new ArrayList<>(firmas.size());
        for (int posicion = 0; posicion < firmas.size(); posicion++) {
            procedencias.add(aFhir(resultado, firmas.get(posicion), posicion + 1));
        }
        return procedencias;
    }

    private Provenance aFhir(Resultado resultado, Validacion validacion, int orden) {
        Provenance procedencia = new Provenance();
        procedencia.setId(identidadDe(resultado, orden).toString());
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
     * El id lógico de la procedencia, <strong>derivado del resultado y del orden de la firma</strong>
     * en vez de sorteado.
     *
     * <p>No es una floritura: el reconciliador del hito 2 tiene que poder regenerar la proyección
     * entera desde el dominio, y con un identificador aleatorio cada pasada crearía otro
     * {@code Provenance} para la misma firma. Al derivarlo, regenerar sobrescribe en vez de duplicar
     * — que es lo que significa que el reconciliador sea idempotente.
     *
     * <p>El orden entra en la derivación porque un crítico tiene dos firmas y necesita dos
     * identidades distintas. Va el orden y no la referencia del facultativo a propósito: el
     * identificador de un recurso público no debe poder leerse al revés para saber quién firmó.
     *
     * @param orden 1 para la revisión inicial, 2 para la contra-revisión del crítico
     */
    public static UUID identidadDe(Resultado resultado, int orden) {
        return UUID.nameUUIDFromBytes(
                ("hispalis:procedencia:validacion:" + resultado.id() + ":" + orden).getBytes(StandardCharsets.UTF_8));
    }
}
