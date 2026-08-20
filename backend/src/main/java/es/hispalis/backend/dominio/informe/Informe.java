package es.hispalis.backend.dominio.informe;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.MarcaDeTiempo;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.resultado.Resultado;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Informe analítico emitido: el conjunto de resultados que se entrega. Agregado raíz.
 *
 * <p>Aquí viven dos condiciones para emitir. La primera es el invariante de §10 del diseño: <strong>un
 * informe solo se emite con todas las líneas del volante resueltas</strong>. Tiene dos mitades y la
 * segunda es la que de verdad hace daño:
 *
 * <ul>
 *   <li><strong>Un informe vacío no es un informe.</strong> Suena obvio y por eso se cuela: llega al
 *       peticionario con la apariencia de una respuesta y no contiene ninguna.
 *   <li><strong>Un informe a medias tampoco.</strong> El volante trae cinco determinaciones, dos
 *       están hechas y el informe sale con esas dos. No parece un error —trae resultados, correctos
 *       y del paciente correcto—, pero se lee como la respuesta a lo que se pidió y quien lo recibe
 *       <em>deja de esperar las otras tres</em>. El vacío se detecta solo; este no.
 * </ul>
 *
 * <p>La segunda es la <strong>validación facultativa</strong>: un informe no entrega resultados que
 * nadie haya revisado. Son condiciones distintas y las dos hacen falta — un volante puede estar
 * entero y sus cifras sin firmar, y un resultado firmado puede pertenecer a un volante a medias.
 *
 * <p><strong>Resuelta no es lo mismo que informada.</strong> Una línea {@link
 * LineaDeLaPeticion#anulada() anulada} cuenta como resuelta: el laboratorio ya dijo —y publicó— que
 * esa determinación no se va a hacer, así que nadie la sigue esperando. Sin esa salida, un volante
 * con una muestra rechazada quedaba bloqueado para siempre, porque no iba a tener resultado nunca.
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
     * @param alcance <strong>todas</strong> las líneas de los volantes que tocan esos resultados,
     *     resueltas o no; vacío si ninguno vino de un volante
     * @param emisor referencia a quien lo firma ({@code Organization/…} o {@code Practitioner/…})
     * @param emitidoEn cuándo se emite; {@code null} se resuelve como ahora
     * @throws ReglaDeNegocioIncumplida si no hay ningún resultado, si alguno sigue sin validar o si
     *     queda alguna línea pendiente
     * @throws DatoInvalido si falta el emisor, si los resultados no son del mismo paciente o si el
     *     alcance no cubre las líneas que citan los resultados
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

        List<LineaDeLaPeticion> lineas = alcance == null ? List.of() : List.copyOf(alcance);
        exigirQueElAlcanceCubraLosResultados(resultados, lineas);
        exigirQueTodosEstenValidados(resultados);
        exigirQueElVolanteEsteTerminado(lineas);

        return new Informe(
                UUID.randomUUID(),
                paciente,
                resultados.stream().map(Resultado::id).toList(),
                emisor.strip(),
                // En milisegundos, que es la única precisión que sobrevive a la ida y vuelta por el
                // almacén: ver MarcaDeTiempo, y el rojo intermitente que la puso ahí.
                MarcaDeTiempo.publicable(emitidoEn));
    }

    /**
     * Comprueba que el alcance recibido incluye, como mínimo, las líneas que citan los resultados.
     *
     * <p>Es el control que impide que este invariante sea decorado. La fábrica no puede ir a buscar
     * las líneas —el núcleo no sabe de repositorios— así que se las tiene que dar quien la llama, y
     * un llamante que construyera el alcance de menos, dejando fuera justo lo que le estorba, pasaría
     * la comprobación de abajo sin un solo error. Que falte una línea citada por un resultado no es
     * un caso de negocio: es que el alcance está mal construido.
     */
    private static void exigirQueElAlcanceCubraLosResultados(
            List<Resultado> resultados, List<LineaDeLaPeticion> lineas) {
        Set<UUID> enElAlcance = lineas.stream().map(LineaDeLaPeticion::id).collect(Collectors.toSet());

        String sinCubrir = resultados.stream()
                .map(Resultado::peticionId)
                .flatMap(Optional::stream)
                .filter(linea -> !enElAlcance.contains(linea))
                .map(UUID::toString)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));

        if (!sinCubrir.isEmpty()) {
            throw new DatoInvalido(
                    ("El alcance del informe no incluye las líneas %s, que sus propios resultados citan. "
                                    + "El alcance tiene que traer el volante entero, no una parte.")
                            .formatted(sinCubrir));
        }
    }

    /**
     * Un informe solo entrega resultados que alguien ha firmado.
     *
     * <p>Lo que sale de un analizador es una cifra: entregarla sin revisar es publicar la avería del
     * reactivo con el sello del laboratorio. Y la revisión no se puede dar por hecha porque el
     * recurso llegue con {@code status = final} — eso lo dice el cliente, no el facultativo.
     *
     * @throws ReglaDeNegocioIncumplida si alguno de los resultados sigue sin validar
     */
    private static void exigirQueTodosEstenValidados(List<Resultado> resultados) {
        String sinValidar = resultados.stream()
                .filter(resultado -> !resultado.estaValidado())
                .map(Resultado::codigoDePrueba)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));

        if (!sinValidar.isEmpty()) {
            throw new ReglaDeNegocioIncumplida(
                    ("El informe no puede entregar %s: nadie los ha validado todavía. Lo que sale del "
                                    + "analizador es una cifra medida, y hasta que un facultativo responde de "
                                    + "ella no es un resultado publicable.")
                            .formatted(sinValidar));
        }
    }

    /** @throws ReglaDeNegocioIncumplida si alguna línea del volante sigue sin resultado */
    private static void exigirQueElVolanteEsteTerminado(List<LineaDeLaPeticion> lineas) {
        String pendientes = lineas.stream()
                .filter(linea -> !linea.resuelta())
                .map(linea -> "%s (volante %s)".formatted(linea.codigoDePrueba(), linea.numeroDePeticion()))
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));

        if (!pendientes.isEmpty()) {
            throw new ReglaDeNegocioIncumplida(
                    ("El volante todavía no está terminado: queda sin resolver %s. Un informe con líneas "
                                    + "pendientes se lee como la respuesta completa, y quien lo recibe deja de "
                                    + "esperar lo que falta.")
                            .formatted(pendientes));
        }
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
