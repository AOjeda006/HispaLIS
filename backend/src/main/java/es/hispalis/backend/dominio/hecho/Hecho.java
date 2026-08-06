package es.hispalis.backend.dominio.hecho;

import es.hispalis.backend.dominio.DatoInvalido;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Un hecho que el laboratorio deja apuntado para publicar.
 *
 * <p>Su carga son <strong>referencias y nada más</strong>: {@code { pacienteId, observationRef, … }}.
 * El invariante 6 del proyecto prohíbe PHI en el bus de eventos, y el sitio donde eso se incumple no
 * es Kafka —es aquí, construyendo la carga—. Por eso la regla vive en esta fábrica y no en el caso de
 * uso: si estuviera arriba, el próximo hecho que alguien añada se saltaría la comprobación sin que
 * nada lo avise.
 *
 * <p>La comprobación es <strong>estructural</strong>, no una lista de palabras prohibidas: cada valor
 * tiene que ser un identificador de este laboratorio (un UUID) o una referencia a uno
 * ({@code Tipo/UUID}). Un nombre, un NHC, un DNI o un NUHSA no tienen esa forma, así que no pueden
 * entrar ni por descuido ni «de paso». Que un dato maestro con identificador no-UUID —una
 * {@code Organization}, un {@code Practitioner}— tampoco pase es deliberado: publicarlos es una
 * decisión que merece pensarse, no colarse.
 */
public final class Hecho {

    /** Un UUID, opcionalmente precedido del tipo de recurso al que pertenece. Nada más. */
    private static final Pattern REFERENCIA = Pattern.compile(
            "([A-Z][A-Za-z]+/)?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Nombre de la clave de partición dentro de la carga. Ver {@link #de}. */
    private static final String PACIENTE = "pacienteId";

    private final UUID id;
    private final TipoDeHecho tipo;
    private final UUID claveDeParticion;
    private final Map<String, String> carga;
    private final Instant creadoEn;

    private Hecho(UUID id, TipoDeHecho tipo, UUID claveDeParticion, Map<String, String> carga, Instant creadoEn) {
        this.id = id;
        this.tipo = tipo;
        this.claveDeParticion = claveDeParticion;
        this.carga = Map.copyOf(carga);
        this.creadoEn = creadoEn;
    }

    /**
     * Compone un hecho sobre un paciente.
     *
     * <p>El paciente es la <strong>clave de partición</strong> (§9): así todo lo de una persona se
     * consume en el orden en que ocurrió, que es lo único que un consumidor necesita para no aplicar
     * una validación antes que el resultado que valida. Y va también <em>dentro</em> de la carga
     * porque quien lee el mensaje no ve la clave con la que se repartió.
     *
     * @param tipo qué ha pasado
     * @param pacienteId de quién; nunca {@code null}
     * @param referencias lo que hay que mirar para saber más, por nombre
     * @throws DatoInvalido si falta el tipo o el paciente, o si alguna referencia no lo es
     */
    public static Hecho de(TipoDeHecho tipo, UUID pacienteId, Map<String, String> referencias) {
        if (tipo == null) {
            throw new DatoInvalido("Un hecho sin tipo no le dice a nadie qué ha pasado.");
        }
        if (pacienteId == null) {
            throw new DatoInvalido(
                    "Un hecho sin paciente no tiene clave de partición, y sin ella se consume desordenado.");
        }

        Map<String, String> carga = new LinkedHashMap<>();
        carga.put(PACIENTE, pacienteId.toString());
        referencias.forEach((nombre, valor) -> carga.put(nombre, exigirQueSeaUnaReferencia(nombre, valor)));

        return new Hecho(UUID.randomUUID(), tipo, pacienteId, carga, Instant.now());
    }

    private static String exigirQueSeaUnaReferencia(String nombre, String valor) {
        if (nombre == null || nombre.isBlank()) {
            throw new DatoInvalido("Una referencia sin nombre no se puede leer al otro lado.");
        }
        if (valor == null || !REFERENCIA.matcher(valor).matches()) {
            throw new DatoInvalido(
                    ("«%s» no es una referencia, así que no puede ir en un hecho: el bus publica identidades y "
                                    + "el que las mire resuelve lo demás contra la API. Datos del paciente, aquí, "
                                    + "nunca — ni siquiera de paso. (Campo «%s».)")
                            .formatted(valor, nombre));
        }
        return valor;
    }

    public UUID id() {
        return id;
    }

    public TipoDeHecho tipo() {
        return tipo;
    }

    /** Por dónde se reparte: el paciente. */
    public UUID claveDeParticion() {
        return claveDeParticion;
    }

    /** Lo que se publica. Inmutable, y solo referencias. */
    public Map<String, String> carga() {
        return carga;
    }

    public Instant creadoEn() {
        return creadoEn;
    }
}
