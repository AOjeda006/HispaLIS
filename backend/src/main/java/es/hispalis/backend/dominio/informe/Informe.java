package es.hispalis.backend.dominio.informe;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.resultado.Resultado;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Informe analítico emitido: el conjunto de resultados que se entrega. Agregado raíz.
 *
 * <p>Aquí vive el invariante de que <strong>un informe vacío no es un informe</strong>. Suena obvio y
 * por eso se cuela: un informe sin resultados llega al peticionario con la apariencia de una
 * respuesta y no contiene ninguna, y quien lo recibe da por hecho que ya no tiene que esperar nada.
 *
 * <p><strong>Pendiente para el cierre del hito</strong> (§10 del diseño): el invariante completo es
 * que el informe solo se emite <em>con todas las líneas de la petición resueltas</em>, no solo con
 * alguna. Eso necesita cruzar las líneas de la petición con sus resultados y está anotado en
 * {@code docs/PLAN.md}; no se adelanta aquí para no ampliar el alcance del ítem 9.
 */
public final class Informe {

    private final UUID id;
    private final UUID pacienteId;
    private final List<UUID> resultadoIds;
    private final String emisor;
    private final Instant emitidoEn;

    private Informe(UUID id, UUID pacienteId, List<UUID> resultadoIds, String emisor, Instant emitidoEn) {
        this.id = id;
        this.pacienteId = pacienteId;
        this.resultadoIds = List.copyOf(resultadoIds);
        this.emisor = emisor;
        this.emitidoEn = emitidoEn;
    }

    /**
     * Emite un informe con los resultados dados.
     *
     * @param resultados los resultados que lo componen; <strong>al menos uno</strong>
     * @param alcance todas las líneas de los volantes que tocan esos resultados, resueltas o no
     * @param emisor referencia a quien lo firma ({@code Organization/…} o {@code Practitioner/…})
     * @param emitidoEn cuándo se emite; {@code null} se resuelve como ahora
     * @throws ReglaDeNegocioIncumplida si no hay ningún resultado
     * @throws DatoInvalido si falta el emisor o los resultados no son del mismo paciente
     */
    public static Informe emitir(
            List<Resultado> resultados, List<LineaDeLaPeticion> alcance, String emisor, Instant emitidoEn) {
        if (resultados == null || resultados.isEmpty()) {
            throw new ReglaDeNegocioIncumplida(
                    "Un informe sin resultados no informa de nada, y quien lo recibe deja de esperar.");
        }
        if (emisor == null || emisor.isBlank()) {
            throw new DatoInvalido("El informe tiene que decir quién lo emite: de eso depende a quién se reclama.");
        }

        UUID paciente = resultados.get(0).pacienteId();
        // Mezclar pacientes en un informe es el peor error posible en un laboratorio: entrega el
        // resultado de una persona bajo el nombre de otra.
        boolean todosDelMismoPaciente =
                resultados.stream().allMatch(resultado -> paciente.equals(resultado.pacienteId()));
        if (!todosDelMismoPaciente) {
            throw new DatoInvalido("Un informe no puede mezclar resultados de pacientes distintos.");
        }

        return new Informe(
                UUID.randomUUID(),
                paciente,
                resultados.stream().map(Resultado::id).toList(),
                emisor.strip(),
                emitidoEn == null ? Instant.now() : emitidoEn);
    }

    /** Reconstruye un informe ya almacenado. Lo usa el repositorio, nunca un caso de uso. */
    public static Informe reconstruir(
            UUID id, UUID pacienteId, List<UUID> resultadoIds, String emisor, Instant emitidoEn) {
        return new Informe(id, pacienteId, resultadoIds, emisor, emitidoEn);
    }

    public UUID id() {
        return id;
    }

    public UUID pacienteId() {
        return pacienteId;
    }

    /** Los resultados que componen el informe, como vista inmutable. */
    public List<UUID> resultadoIds() {
        return resultadoIds;
    }

    public String emisor() {
        return emisor;
    }

    public Instant emitidoEn() {
        return emitidoEn;
    }
}
