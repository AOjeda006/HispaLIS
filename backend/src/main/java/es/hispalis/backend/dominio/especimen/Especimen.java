package es.hispalis.backend.dominio.especimen;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import java.util.Optional;
import java.util.UUID;

/**
 * Muestra biológica recibida en el laboratorio. Agregado raíz.
 *
 * <p>Aquí vive <strong>el invariante que FHIR no puede expresar</strong> (§10 del diseño): una
 * muestra rechazada no produce resultados. Nada en el estándar lo impide —un {@code Observation} que
 * apunta a un {@code Specimen} con {@code status = unsatisfactory} es un recurso perfectamente
 * válido—, porque lo que está mal no es ninguno de los dos por separado sino su combinación, y eso
 * solo lo sabe el laboratorio.
 *
 * <p>Está aquí y no en el {@code ResourceProvider} por una razón concreta: tiene que valer igual
 * venga la petición de la web, del motor de integración o de un script de mantenimiento. Un
 * invariante que solo se comprueba en una puerta no es un invariante, es una validación de esa
 * puerta.
 */
public final class Especimen {

    private final UUID id;
    private final NumeroDeAcceso numeroDeAcceso;
    private final UUID pacienteId;
    private final String tipo;
    private final EstadoDeEspecimen estado;
    private final String motivoDeRechazo;

    private Especimen(
            UUID id,
            NumeroDeAcceso numeroDeAcceso,
            UUID pacienteId,
            String tipo,
            EstadoDeEspecimen estado,
            String motivoDeRechazo) {
        this.id = id;
        this.numeroDeAcceso = numeroDeAcceso;
        this.pacienteId = pacienteId;
        this.tipo = tipo;
        this.estado = estado;
        this.motivoDeRechazo = motivoDeRechazo;
    }

    /**
     * Registra la recepción de una muestra.
     *
     * @param numeroDeAcceso código con el que la muestra circula físicamente; sin él no hay
     *     trazabilidad entre el tubo y el resultado
     * @param pacienteId de quién es la muestra
     * @param tipo tipo de muestra, codificado en SNOMED CT
     * @param estado situación en la que se recibe
     * @param motivoDeRechazo obligatorio si el estado es {@link EstadoDeEspecimen#RECHAZADA}
     * @throws DatoInvalido si se rechaza sin motivo, o si falta el tipo o el paciente
     */
    public static Especimen registrar(
            NumeroDeAcceso numeroDeAcceso,
            UUID pacienteId,
            String tipo,
            EstadoDeEspecimen estado,
            String motivoDeRechazo) {
        if (pacienteId == null) {
            throw new DatoInvalido("Una muestra sin paciente no es trazable.");
        }
        if (tipo == null || tipo.isBlank()) {
            throw new DatoInvalido("Hay que decir de qué tipo es la muestra.");
        }
        // Rechazar sin decir por qué obliga al peticionario a llamar por teléfono. Es la misma regla
        // que la invariante `hlis-esp-1` de la guía, aquí en el sitio que manda.
        if (estado == EstadoDeEspecimen.RECHAZADA && (motivoDeRechazo == null || motivoDeRechazo.isBlank())) {
            throw new DatoInvalido("Una muestra rechazada tiene que documentar el motivo del rechazo.");
        }
        return new Especimen(
                UUID.randomUUID(),
                numeroDeAcceso,
                pacienteId,
                tipo.strip(),
                estado,
                motivoDeRechazo == null || motivoDeRechazo.isBlank() ? null : motivoDeRechazo.strip());
    }

    /** Reconstruye una muestra ya almacenada. Lo usa el repositorio, nunca un caso de uso. */
    public static Especimen reconstruir(
            UUID id,
            NumeroDeAcceso numeroDeAcceso,
            UUID pacienteId,
            String tipo,
            EstadoDeEspecimen estado,
            String motivoDeRechazo) {
        return new Especimen(id, numeroDeAcceso, pacienteId, tipo, estado, motivoDeRechazo);
    }

    /**
     * Comprueba que de esta muestra se puede informar un resultado, y falla si no.
     *
     * <p>Se llama <em>exigir</em> y no <em>puede</em> a propósito: quien la invoca no tiene que
     * acordarse de mirar el valor devuelto. Un booleano ignorado es un invariante que no existe.
     *
     * @throws ReglaDeNegocioIncumplida si la muestra fue rechazada o ya no está disponible
     */
    public void exigirQuePuedeProducirResultados() {
        if (estado.permiteInformarResultados()) {
            return;
        }
        if (estado == EstadoDeEspecimen.RECHAZADA) {
            throw new ReglaDeNegocioIncumplida("La muestra %s fue rechazada (%s), así que no puede producir resultados."
                    .formatted(numeroDeAcceso.valor(), motivoDeRechazo));
        }
        throw new ReglaDeNegocioIncumplida(
                "La muestra %s no está disponible para analizar.".formatted(numeroDeAcceso.valor()));
    }

    public UUID id() {
        return id;
    }

    public NumeroDeAcceso numeroDeAcceso() {
        return numeroDeAcceso;
    }

    public UUID pacienteId() {
        return pacienteId;
    }

    public String tipo() {
        return tipo;
    }

    public EstadoDeEspecimen estado() {
        return estado;
    }

    public Optional<String> motivoDeRechazo() {
        return Optional.ofNullable(motivoDeRechazo);
    }
}
