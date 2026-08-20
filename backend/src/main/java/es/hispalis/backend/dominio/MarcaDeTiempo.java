package es.hispalis.backend.dominio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * La precisión con la que el laboratorio fecha lo que publica: <strong>milisegundos</strong>.
 *
 * <p>No es una preferencia de formato. Un instante recorre tres sitios con tres precisiones distintas
 * y solo una de las tres sobrevive al viaje entero:
 *
 * <ul>
 *   <li><strong>El reloj</strong> da nanosegundos. {@code Instant.now()} sobre un servidor Linux
 *       devuelve los nueve dígitos.
 *   <li><strong>El almacén</strong> guarda microsegundos: {@code timestamptz} de PostgreSQL. Lo que
 *       sobra <em>no lo trunca, lo redondea</em>, y ese es el detalle que hace daño.
 *   <li><strong>Lo publicado</strong> lleva milisegundos: el tipo {@code instant} de FHIR, que es el
 *       de {@code DiagnosticReport.issued} y {@code Provenance.recorded}.
 * </ul>
 *
 * <p>La escritura publica el recurso desde el agregado <strong>en memoria</strong> y el reconciliador
 * lo regenera desde el agregado <strong>releído</strong>. Si el reloj cae en el último medio
 * microsegundo de un milisegundo, el redondeo del almacén cruza la frontera y las dos proyecciones
 * del mismo recurso se separan en un milisegundo: una divergencia que nadie provocó, que no se repara
 * sola y que hace mentir a la vía oficial de recuperación. Son 500 ns de cada millón —uno de cada dos
 * mil recursos fechados— y así apareció: como un rojo de CI cada muchas ejecuciones (ADR-0045).
 *
 * <p>De las tres precisiones, la del milisegundo es la única que da lo mismo a la ida y a la vuelta,
 * porque el almacén la representa exacta y FHIR la publica entera. Así que el agregado nace ya en
 * ella y nunca guarda más fino de lo que su almacén sabe devolver.
 */
public final class MarcaDeTiempo {

    private MarcaDeTiempo() {}

    /**
     * La marca tal y como el laboratorio la va a guardar y publicar.
     *
     * @param cuando el instante; {@code null} se resuelve como ahora
     * @return el mismo instante en milisegundos, hacia abajo — nunca hacia un futuro que no ocurrió
     */
    public static Instant publicable(Instant cuando) {
        return (cuando == null ? Instant.now() : cuando).truncatedTo(ChronoUnit.MILLIS);
    }
}
