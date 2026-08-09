package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.peticion.Peticion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resultado de una determinación analítica. Agregado raíz.
 *
 * <p>Se crea <strong>a partir de la muestra y de la línea</strong>, no junto a ellas: la fábrica
 * recibe los dos agregados y les exige, en ese orden, que la muestra pueda producir resultados y que
 * la línea siga admitiéndolos. Así el invariante no depende de que quien llame se acuerde de
 * comprobarlo — no hay forma de obtener un resultado de una muestra rechazada ni de una línea
 * anulada, porque la única puerta pasa por esas dos comprobaciones.
 *
 * <p>Un valor numérico va <strong>siempre con su unidad UCUM</strong>. Una cifra sin unidad no
 * significa nada: «4,2» puede ser normal o incompatible con la vida según de qué se hable.
 */
public final class Resultado {

    private final UUID id;
    private final UUID especimenId;
    private final UUID pacienteId;
    private final UUID peticionId;
    private final String codigoDePrueba;
    private final BigDecimal valor;
    private final String unidadUcum;
    private final String valorTextual;
    private final Medicion medicion;
    private final Validacion validacion;
    private final Disparo disparo;

    private Resultado(
            UUID id,
            UUID especimenId,
            UUID pacienteId,
            UUID peticionId,
            String codigoDePrueba,
            BigDecimal valor,
            String unidadUcum,
            String valorTextual,
            Medicion medicion,
            Validacion validacion,
            Disparo disparo) {
        this.id = id;
        this.especimenId = especimenId;
        this.pacienteId = pacienteId;
        this.peticionId = peticionId;
        this.codigoDePrueba = codigoDePrueba;
        this.valor = valor;
        this.unidadUcum = unidadUcum;
        this.valorTextual = valorTextual;
        this.medicion = medicion == null ? Medicion.sinConstancia() : medicion;
        this.validacion = validacion;
        this.disparo = disparo;
    }

    /**
     * Informa un resultado cuantitativo de una muestra.
     *
     * @param especimen la muestra de la que procede; <strong>se le exige que pueda producir
     *     resultados</strong> antes de nada
     * @param linea la línea de petición que lo motivó, o {@code null} si no vino de ninguna;
     *     <strong>se le exige que siga admitiendo resultados</strong>
     * @param codigoDePrueba código del catálogo del laboratorio (p. ej. {@code GLU})
     * @param valor la cifra medida
     * @param unidadUcum unidad UCUM en la que está la cifra
     * @param medicion cuándo se midió y quién lo hizo; {@link Medicion#sinConstancia()} si no consta
     * @param disparo de dónde viene esta determinación, o {@code null} si la pidieron por volante
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si la muestra fue rechazada o no
     *     está disponible, o si la línea está anulada
     * @throws DatoInvalido si falta el código de prueba, la cifra o la unidad
     */
    public static Resultado informarCuantitativo(
            Especimen especimen,
            Peticion linea,
            String codigoDePrueba,
            BigDecimal valor,
            String unidadUcum,
            Medicion medicion,
            Disparo disparo) {
        exigirQueSePuedaInformar(especimen, linea);

        if (codigoDePrueba == null || codigoDePrueba.isBlank()) {
            throw new DatoInvalido("Un resultado sin código de prueba no dice qué se ha medido.");
        }
        if (valor == null) {
            throw new DatoInvalido("Un resultado cuantitativo necesita una cifra.");
        }
        if (unidadUcum == null || unidadUcum.isBlank()) {
            throw new DatoInvalido(
                    "Un valor numérico va siempre con su unidad UCUM: una cifra sola no significa nada.");
        }
        return new Resultado(
                UUID.randomUUID(),
                especimen.id(),
                especimen.pacienteId(),
                identidadDe(linea),
                codigoDePrueba.strip(),
                valor,
                unidadUcum.strip(),
                null,
                medicion,
                // Recién salido del analizador. Lo que hay aquí es una cifra medida; que sea un
                // resultado publicable lo decide después una persona.
                null,
                disparo);
    }

    /**
     * Informa un resultado textual: lo que no se deja codificar ni medir.
     *
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si la muestra no puede producir
     *     resultados o si la línea está anulada
     */
    public static Resultado informarTextual(
            Especimen especimen,
            Peticion linea,
            String codigoDePrueba,
            String texto,
            Medicion medicion,
            Disparo disparo) {
        exigirQueSePuedaInformar(especimen, linea);

        if (codigoDePrueba == null || codigoDePrueba.isBlank()) {
            throw new DatoInvalido("Un resultado sin código de prueba no dice qué se ha medido.");
        }
        if (texto == null || texto.isBlank()) {
            throw new DatoInvalido("Un resultado textual sin texto no informa de nada.");
        }
        return new Resultado(
                UUID.randomUUID(),
                especimen.id(),
                especimen.pacienteId(),
                identidadDe(linea),
                codigoDePrueba.strip(),
                null,
                null,
                texto.strip(),
                medicion,
                null,
                disparo);
    }

    /**
     * Firma el resultado: una persona lo ha revisado y responde de él.
     *
     * <p>Es lo que convierte una cifra en un resultado publicable, y de aquí cuelgan el
     * {@code ORU^R01} saliente hacia el HIS y la notificación EDO. Devuelve un agregado nuevo: el
     * original no se toca.
     *
     * @throws ReglaDeNegocioIncumplida si ya estaba validado
     * @throws DatoInvalido si no se dice quién valida
     */
    public Resultado validar(String facultativo, Instant cuando) {
        // Revalidar no es corregir. Si valiera, la segunda firma taparía a la primera y el rastro de
        // quién respondió del resultado quedaría reescrito sin dejar constancia de que hubo otro.
        // Corregir un resultado ya validado es otra operación, con sus propias reglas.
        if (validacion != null) {
            throw new ReglaDeNegocioIncumplida(
                    "El resultado %s ya está validado por %s: revalidar taparía la primera firma."
                            .formatted(codigoDePrueba, validacion.facultativo()));
        }
        return new Resultado(
                id,
                especimenId,
                pacienteId,
                peticionId,
                codigoDePrueba,
                valor,
                unidadUcum,
                valorTextual,
                medicion,
                Validacion.por(facultativo, cuando),
                disparo);
    }

    /**
     * Las dos condiciones que tienen que darse para que exista un resultado, en el orden en que un
     * laboratorio las descubre: primero el tubo, después el volante.
     *
     * <p>La línea llega como agregado y no como identificador a propósito. Con un {@code UUID} el
     * invariante no se podría comprobar aquí y acabaría en el caso de uso, que es donde deja de
     * valer en cuanto aparece otra puerta de entrada — y el hito 2 trae una, el motor de integración.
     */
    private static void exigirQueSePuedaInformar(Especimen especimen, Peticion linea) {
        especimen.exigirQuePuedeProducirResultados();
        if (linea != null) {
            linea.exigirQueAdmiteResultados();
        }
    }

    private static UUID identidadDe(Peticion linea) {
        return linea == null ? null : linea.id();
    }

    /** Reconstruye un resultado ya almacenado. Lo usa el repositorio, nunca un caso de uso. */
    public static Resultado reconstruir(
            UUID id,
            UUID especimenId,
            UUID pacienteId,
            UUID peticionId,
            String codigoDePrueba,
            BigDecimal valor,
            String unidadUcum,
            String valorTextual,
            Medicion medicion,
            Validacion validacion,
            Disparo disparo) {
        return new Resultado(
                id,
                especimenId,
                pacienteId,
                peticionId,
                codigoDePrueba,
                valor,
                unidadUcum,
                valorTextual,
                medicion,
                validacion,
                disparo);
    }

    public UUID id() {
        return id;
    }

    public UUID especimenId() {
        return especimenId;
    }

    public UUID pacienteId() {
        return pacienteId;
    }

    /**
     * La línea de petición que lo motivó, si se informó.
     *
     * <p>Es opcional porque un resultado puede llegar sin petición previa: una repetición de control
     * o una determinación añadida en el laboratorio existen aunque nadie las pidiera por volante.
     */
    public Optional<UUID> peticionId() {
        return Optional.ofNullable(peticionId);
    }

    public String codigoDePrueba() {
        return codigoDePrueba;
    }

    public Optional<BigDecimal> valor() {
        return Optional.ofNullable(valor);
    }

    public Optional<String> unidadUcum() {
        return Optional.ofNullable(unidadUcum);
    }

    public Optional<String> valorTextual() {
        return Optional.ofNullable(valorTextual);
    }

    /** Cuándo se hizo la determinación y quién la hizo. Nunca {@code null}; puede no constar. */
    public Medicion medicion() {
        return medicion;
    }

    /** La firma facultativa, si ya se ha producido. */
    public Optional<Validacion> validacion() {
        return Optional.ofNullable(validacion);
    }

    /**
     * De dónde viene esta determinación, cuando no la pidió nadie por volante.
     *
     * <p>Vacío es lo normal: la inmensa mayoría de los resultados existen porque estaban en el
     * volante.
     */
    public Optional<Disparo> disparadoPor() {
        return Optional.ofNullable(disparo);
    }

    /**
     * Si la cifra se sale de la normalidad que publica el laboratorio. Es la condición de la refleja.
     *
     * <p><strong>Con varios rangos hace falta salirse de todos.</strong> Una prueba puede tener rango
     * por sexo y aquí no se conoce al paciente —el resultado guarda su identificador, no su
     * filiación—, así que solo cuenta como alterado lo que lo estaría <em>para cualquiera</em>. Es
     * deliberadamente conservador: añadir una prueba que no tocaba le cuesta al paciente otra
     * extracción, así que ante la duda no se añade. Con un solo rango, que es el caso de la TSH, la
     * respuesta es exacta.
     *
     * <p>Los rangos en <strong>otra unidad</strong> no se comparan: se ignoran, y si no queda
     * ninguno comparable la respuesta es que no consta alteración. Convertir unidades a ojo aquí
     * sería inventar. Es la misma regla que en {@link UmbralCritico#alcanzaA}, con una diferencia
     * deliberada: allí se lanza, porque callarse esconde un valor crítico; aquí se calla, porque lo
     * único que se pierde es una prueba añadida.
     *
     * @param rangos los rangos publicados para esta prueba; puede venir vacío (las cualitativas)
     */
    public boolean estaFueraDeRango(List<RangoDeReferencia> rangos) {
        if (valor == null || unidadUcum == null) {
            return false;
        }
        List<RangoDeReferencia> comparables = rangos.stream()
                .filter(rango -> unidadUcum.equals(rango.unidadUcum()))
                .toList();

        return !comparables.isEmpty()
                && comparables.stream()
                        .allMatch(rango -> valor.compareTo(rango.bajo()) < 0 || valor.compareTo(rango.alto()) > 0);
    }

    /** Se deriva de la firma y no se guarda aparte: ver {@link EstadoDeResultado}. */
    public EstadoDeResultado estado() {
        return validacion == null ? EstadoDeResultado.PRELIMINAR : EstadoDeResultado.VALIDADO;
    }

    public boolean estaValidado() {
        return validacion != null;
    }
}
