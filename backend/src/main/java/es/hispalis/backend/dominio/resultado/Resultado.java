package es.hispalis.backend.dominio.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.especimen.Especimen;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Resultado de una determinación analítica. Agregado raíz.
 *
 * <p>Se crea <strong>a partir de la muestra</strong>, no junto a ella: la fábrica recibe el
 * {@link Especimen} y le exige que pueda producir resultados antes de construir nada. Así el
 * invariante no depende de que quien llame se acuerde de comprobarlo — no hay forma de obtener un
 * resultado de una muestra rechazada, porque la única puerta pasa por esa comprobación.
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

    private Resultado(
            UUID id,
            UUID especimenId,
            UUID pacienteId,
            UUID peticionId,
            String codigoDePrueba,
            BigDecimal valor,
            String unidadUcum,
            String valorTextual) {
        this.id = id;
        this.especimenId = especimenId;
        this.pacienteId = pacienteId;
        this.peticionId = peticionId;
        this.codigoDePrueba = codigoDePrueba;
        this.valor = valor;
        this.unidadUcum = unidadUcum;
        this.valorTextual = valorTextual;
    }

    /**
     * Informa un resultado cuantitativo de una muestra.
     *
     * @param especimen la muestra de la que procede; <strong>se le exige que pueda producir
     *     resultados</strong> antes de nada
     * @param codigoDePrueba código del catálogo del laboratorio (p. ej. {@code GLU})
     * @param valor la cifra medida
     * @param unidadUcum unidad UCUM en la que está la cifra
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si la muestra fue rechazada o no
     *     está disponible
     * @throws DatoInvalido si falta el código de prueba, la cifra o la unidad
     */
    public static Resultado informarCuantitativo(
            Especimen especimen, UUID peticionId, String codigoDePrueba, BigDecimal valor, String unidadUcum) {
        especimen.exigirQuePuedeProducirResultados();

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
                peticionId,
                codigoDePrueba.strip(),
                valor,
                unidadUcum.strip(),
                null);
    }

    /**
     * Informa un resultado textual: lo que no se deja codificar ni medir.
     *
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si la muestra no puede producir
     *     resultados
     */
    public static Resultado informarTextual(Especimen especimen, UUID peticionId, String codigoDePrueba, String texto) {
        especimen.exigirQuePuedeProducirResultados();

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
                peticionId,
                codigoDePrueba.strip(),
                null,
                null,
                texto.strip());
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
            String valorTextual) {
        return new Resultado(id, especimenId, pacienteId, peticionId, codigoDePrueba, valor, unidadUcum, valorTextual);
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
}
