package es.hispalis.backend.dominio.peticion;

import es.hispalis.backend.dominio.DatoInvalido;
import java.time.Instant;
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
 */
public final class Peticion {

    private final UUID id;
    private final String numeroDePeticion;
    private final UUID pacienteId;
    private final String codigoDePrueba;
    private final String solicitante;
    private final Instant solicitadaEn;

    private Peticion(
            UUID id,
            String numeroDePeticion,
            UUID pacienteId,
            String codigoDePrueba,
            String solicitante,
            Instant solicitadaEn) {
        this.id = id;
        this.numeroDePeticion = numeroDePeticion;
        this.pacienteId = pacienteId;
        this.codigoDePrueba = codigoDePrueba;
        this.solicitante = solicitante;
        this.solicitadaEn = solicitadaEn;
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
                solicitadaEn == null ? Instant.now() : solicitadaEn);
    }

    /** Reconstruye una petición ya almacenada. Lo usa el repositorio, nunca un caso de uso. */
    public static Peticion reconstruir(
            UUID id,
            String numeroDePeticion,
            UUID pacienteId,
            String codigoDePrueba,
            String solicitante,
            Instant solicitadaEn) {
        return new Peticion(id, numeroDePeticion, pacienteId, codigoDePrueba, solicitante, solicitadaEn);
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
}
