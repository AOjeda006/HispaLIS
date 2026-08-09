package es.hispalis.backend.dominio.peticion;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Línea de una petición analítica: una prueba solicitada sobre un paciente. Agregado raíz.
 *
 * <p>Lo que el laboratorio y el peticionario llaman «la petición» en la conversación diaria son
 * <strong>varias líneas que comparten {@code numeroDePeticion}</strong> — un hemograma, una glucosa
 * y una TSH pedidos en el mismo volante—. Modelarlo así, y no como una petición con líneas dentro,
 * es lo que permite que cada prueba avance a su ritmo: unas se informan hoy y otras tardan tres días.
 *
 * <p>El solicitante se guarda como <strong>referencia opaca</strong>. El facultativo peticionario y
 * la organización son datos maestros del laboratorio, no agregados con invariantes propios (§10), y
 * modelarlos como tales sería inventar complejidad que el negocio no pide.
 *
 * <p><strong>La línea tiene estado, y existe por una razón concreta:</strong> el invariante del
 * informe bloquea la emisión mientras quede una línea sin resolver, y una muestra rechazada no
 * produce ninguna. Sin {@link #anular}, un volante con una muestra rechazada quedaba bloqueado para
 * siempre a la espera de una extracción que puede no llegar nunca. Anular no borra: la línea se
 * sigue publicando —como {@code revoked}— y se sigue leyendo; lo único que cambia es que deja de
 * estar pendiente.
 */
public final class Peticion {

    private final UUID id;
    private final String numeroDePeticion;
    private final UUID pacienteId;
    private final String codigoDePrueba;
    private final String solicitante;
    private final Instant solicitadaEn;
    private final EstadoDeLinea estado;
    private final String motivoDeAnulacion;
    private final Instant anuladaEn;
    private final UUID disparadaPor;
    private final String motivoDelDisparo;

    private Peticion(
            UUID id,
            String numeroDePeticion,
            UUID pacienteId,
            String codigoDePrueba,
            String solicitante,
            Instant solicitadaEn,
            EstadoDeLinea estado,
            String motivoDeAnulacion,
            Instant anuladaEn,
            UUID disparadaPor,
            String motivoDelDisparo) {
        this.id = id;
        this.numeroDePeticion = numeroDePeticion;
        this.pacienteId = pacienteId;
        this.codigoDePrueba = codigoDePrueba;
        this.solicitante = solicitante;
        this.solicitadaEn = solicitadaEn;
        this.estado = estado;
        this.motivoDeAnulacion = motivoDeAnulacion;
        this.anuladaEn = anuladaEn;
        this.disparadaPor = disparadaPor;
        this.motivoDelDisparo = motivoDelDisparo;
    }

    /**
     * Registra una línea de petición.
     *
     * @param numeroDePeticion número que agrupa todas las líneas del mismo volante
     * @param pacienteId sobre quién se pide
     * @param codigoDePrueba código del catálogo del laboratorio
     * @param solicitante referencia a quien la pide, tal y como llegó ({@code Practitioner/…})
     * @param solicitadaEn cuándo se pidió; {@code null} se resuelve como ahora
     * @throws DatoInvalido si falta el número, el paciente, la prueba o el solicitante
     */
    public static Peticion registrar(
            String numeroDePeticion, UUID pacienteId, String codigoDePrueba, String solicitante, Instant solicitadaEn) {
        if (numeroDePeticion == null || numeroDePeticion.isBlank()) {
            throw new DatoInvalido("La petición necesita número: es lo que agrupa sus líneas.");
        }
        if (pacienteId == null) {
            throw new DatoInvalido("Una petición sin paciente no se puede atender.");
        }
        if (codigoDePrueba == null || codigoDePrueba.isBlank()) {
            throw new DatoInvalido("Hay que decir qué prueba se pide, con un código del catálogo.");
        }
        // Sin peticionario no se sabe a quién devolver el informe ni a quién preguntar una duda
        // clínica. No es burocracia: es la mitad del circuito.
        if (solicitante == null || solicitante.isBlank()) {
            throw new DatoInvalido("La petición tiene que decir quién la solicita.");
        }
        return new Peticion(
                UUID.randomUUID(),
                numeroDePeticion.strip(),
                pacienteId,
                codigoDePrueba.strip(),
                solicitante.strip(),
                solicitadaEn == null ? Instant.now() : solicitadaEn,
                EstadoDeLinea.ACTIVA,
                null,
                null,
                null,
                null);
    }

    /**
     * Añade una línea que <strong>no pidió el peticionario</strong>: la prueba refleja.
     *
     * <p>Hereda el volante, el paciente y el solicitante de la línea que la disparó, y no es un
     * atajo: R5 lo dice explícitamente en la definición del código {@code reflex} — la petición
     * original es «the one that provided the authorization». El laboratorio decide <em>qué</em> se
     * añade; quién lo autorizó sigue siendo el clínico que firmó el volante.
     *
     * <p>Por eso mismo, una determinación informada <strong>sin línea</strong> no puede disparar
     * ninguna refleja: no hay volante del que colgar la autorización ni a quién devolverle el
     * resultado añadido. Es una limitación deliberada, no un hueco.
     *
     * @param queLaDispara la línea de la prueba que salió alterada
     * @param codigoReflejo la prueba que el catálogo manda añadir
     * @param resultadoQueLaDispara el resultado alterado, que es lo que se enlaza después
     * @param motivo la frase del catálogo que explica por qué existe
     * @throws DatoInvalido si falta cualquiera de los tres
     */
    public static Peticion refleja(
            Peticion queLaDispara, String codigoReflejo, UUID resultadoQueLaDispara, String motivo) {
        if (queLaDispara == null) {
            throw new DatoInvalido("Una refleja cuelga de la línea que la disparó: sin ella no hay volante.");
        }
        if (codigoReflejo == null || codigoReflejo.isBlank()) {
            throw new DatoInvalido("Hay que decir qué prueba se añade.");
        }
        if (resultadoQueLaDispara == null) {
            throw new DatoInvalido("Una refleja tiene que decir qué resultado la provocó.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new DatoInvalido(
                    ("La refleja de «%s» no trae motivo, y sin él aparecería en el informe una prueba que "
                                    + "nadie pidió y que no se puede explicar.")
                            .formatted(queLaDispara.codigoDePrueba));
        }
        return new Peticion(
                UUID.randomUUID(),
                queLaDispara.numeroDePeticion,
                queLaDispara.pacienteId,
                codigoReflejo.strip(),
                queLaDispara.solicitante,
                Instant.now(),
                EstadoDeLinea.ACTIVA,
                null,
                null,
                resultadoQueLaDispara,
                motivo.strip());
    }

    /**
     * Retira la línea: el laboratorio no va a hacer esa determinación.
     *
     * @param yaTieneResultados si contra esta línea consta ya algún resultado. El agregado
     *     <strong>no puede saberlo por su cuenta</strong> —los resultados son otro agregado— así que
     *     se lo tiene que dar quien lo llama, igual que el alcance del informe. La regla la decide
     *     aquí el dominio, no el caso de uso.
     * @param motivo por qué se retira; obligatorio
     * @param cuando cuándo se retira; {@code null} se resuelve como ahora
     * @return la línea anulada; la original no se modifica
     * @throws ReglaDeNegocioIncumplida si ya está anulada o si ya tiene resultados
     * @throws DatoInvalido si no se da motivo
     */
    public Peticion anular(boolean yaTieneResultados, String motivo, Instant cuando) {
        if (estado == EstadoDeLinea.ANULADA) {
            throw new ReglaDeNegocioIncumplida("La línea %s del volante %s ya estaba anulada (%s)."
                    .formatted(codigoDePrueba, numeroDePeticion, motivoDeAnulacion));
        }
        // Anular algo ya informado dejaría el resultado publicado colgando de una línea que dice que
        // no se hizo, y contradiría al informe que lo hubiera entregado. Lo que se corrige entonces
        // es el resultado, no la línea.
        if (yaTieneResultados) {
            throw new ReglaDeNegocioIncumplida(
                    ("La línea %s del volante %s ya tiene resultado informado, así que no se anula: lo que "
                                    + "haya que corregir se corrige sobre el resultado.")
                            .formatted(codigoDePrueba, numeroDePeticion));
        }
        // Sin motivo, el peticionario ve una prueba que pidió y que no se le entrega, y no sabe si
        // reclamarla o repetir la extracción.
        if (motivo == null || motivo.isBlank()) {
            throw new DatoInvalido("Anular una línea sin decir por qué deja al peticionario sin saber qué hacer.");
        }
        return new Peticion(
                id,
                numeroDePeticion,
                pacienteId,
                codigoDePrueba,
                solicitante,
                solicitadaEn,
                EstadoDeLinea.ANULADA,
                motivo.strip(),
                cuando == null ? Instant.now() : cuando,
                disparadaPor,
                motivoDelDisparo);
    }

    /**
     * Comprueba que esta línea todavía admite resultados, y falla si no.
     *
     * <p>Se llama <em>exigir</em> y no <em>puede</em> por lo mismo que en
     * {@link es.hispalis.backend.dominio.especimen.Especimen#exigirQuePuedeProducirResultados()}: un
     * booleano que se puede ignorar no es un invariante.
     *
     * @throws ReglaDeNegocioIncumplida si la línea está anulada
     */
    public void exigirQueAdmiteResultados() {
        if (estado.admiteResultados()) {
            return;
        }
        throw new ReglaDeNegocioIncumplida(
                ("La línea %s del volante %s se anuló (%s), así que no puede producir resultados: el "
                                + "laboratorio ya dijo que esa determinación no se iba a hacer.")
                        .formatted(codigoDePrueba, numeroDePeticion, motivoDeAnulacion));
    }

    /** Reconstruye una petición ya almacenada. Lo usa el repositorio, nunca un caso de uso. */
    public static Peticion reconstruir(
            UUID id,
            String numeroDePeticion,
            UUID pacienteId,
            String codigoDePrueba,
            String solicitante,
            Instant solicitadaEn,
            EstadoDeLinea estado,
            String motivoDeAnulacion,
            Instant anuladaEn,
            UUID disparadaPor,
            String motivoDelDisparo) {
        return new Peticion(
                id,
                numeroDePeticion,
                pacienteId,
                codigoDePrueba,
                solicitante,
                solicitadaEn,
                estado,
                motivoDeAnulacion,
                anuladaEn,
                disparadaPor,
                motivoDelDisparo);
    }

    public UUID id() {
        return id;
    }

    public String numeroDePeticion() {
        return numeroDePeticion;
    }

    public UUID pacienteId() {
        return pacienteId;
    }

    public String codigoDePrueba() {
        return codigoDePrueba;
    }

    public String solicitante() {
        return solicitante;
    }

    public Instant solicitadaEn() {
        return solicitadaEn;
    }

    public EstadoDeLinea estado() {
        return estado;
    }

    public boolean estaAnulada() {
        return estado == EstadoDeLinea.ANULADA;
    }

    public Optional<String> motivoDeAnulacion() {
        return Optional.ofNullable(motivoDeAnulacion);
    }

    public Optional<Instant> anuladaEn() {
        return Optional.ofNullable(anuladaEn);
    }

    /**
     * El resultado que provocó esta línea, si la añadió el laboratorio por su cuenta.
     *
     * <p>Vacío en todo lo que se pidió por volante, que es la inmensa mayoría.
     */
    public Optional<UUID> disparadaPor() {
        return Optional.ofNullable(disparadaPor);
    }

    /** Por qué la añadió, en la frase que el catálogo trae redactada. */
    public Optional<String> motivoDelDisparo() {
        return Optional.ofNullable(motivoDelDisparo);
    }
}
