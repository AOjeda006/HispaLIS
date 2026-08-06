package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.peticion.Peticion;
import java.math.BigDecimal;
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

    private Resultado(
            UUID id,
            UUID especimenId,
            UUID pacienteId,
            UUID peticionId,
            String codigoDePrueba,
            BigDecimal valor,
            String unidadUcum,
            String valorTextual,
            Medicion medicion) {
        this.id = id;
        this.especimenId = especimenId;
        this.pacienteId = pacienteId;
        this.peticionId = peticionId;
        this.codigoDePrueba = codigoDePrueba;
        this.valor = valor;
        this.unidadUcum = unidadUcum;
        this.valorTextual = valorTextual;
        this.medicion = medicion == null ? Medicion.sinConstancia() : medicion;
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
            Medicion medicion) {
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
                medicion);
    }

    /**
     * Informa un resultado textual: lo que no se deja codificar ni medir.
     *
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si la muestra no puede producir
     *     resultados o si la línea está anulada
     */
    public static Resultado informarTextual(
            Especimen especimen, Peticion linea, String codigoDePrueba, String texto, Medicion medicion) {
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
                medicion);
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
            Medicion medicion) {
        return new Resultado(
                id, especimenId, pacienteId, peticionId, codigoDePrueba, valor, unidadUcum, valorTextual, medicion);
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
}
