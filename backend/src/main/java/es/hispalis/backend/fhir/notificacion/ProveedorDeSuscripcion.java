package es.hispalis.backend.fhir.notificacion;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import java.util.List;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Bundle.BundleType;
import org.hl7.fhir.r5.model.Integer64Type;
import org.hl7.fhir.r5.model.Subscription;
import org.hl7.fhir.r5.model.SubscriptionStatus;
import org.hl7.fhir.r5.model.SubscriptionStatus.SubscriptionNotificationType;
import org.springframework.stereotype.Component;

/**
 * Las dos operaciones que R5 define sobre {@code Subscription}: {@code $status} y {@code $events}.
 *
 * <p><strong>HAPI 8.10 no las trae.</strong> Su motor de suscripciones publica
 * {@code $trigger-subscription} y para de contar; comprobado por reflexión sobre
 * {@code SubscriptionResourceProvider}, que solo expone {@code search}. Así que se implementan aquí,
 * y eso obliga a decidir algo que HAPI habría decidido por nosotros: {@code $events} necesita que las
 * notificaciones ya entregadas <strong>estén guardadas</strong>. Sin la tabla del ítem 44, la
 * operación no se podría contestar más que con una lista vacía.
 *
 * <p>Va como proveedor suelto y no como {@code ProveedorPropio} a propósito. Un {@code ProveedorPropio}
 * <em>sustituye</em> al de HAPI y arrastra las dos puertas de {@code EscrituraSoloPorElNucleo} y
 * {@code SoloLosVerbosQueElNucleoGobierna}, que existen para los recursos con agregado detrás.
 * {@code Subscription} no lo tiene —es configuración de un cliente, no dato clínico—, así que se
 * escribe por el proveedor estándar y aquí solo se añaden dos operaciones.
 */
@Component
public class ProveedorDeSuscripcion {

    /** Sin tope explícito, {@code $events} devuelve desde el primero. */
    private static final long DESDE_EL_PRINCIPIO = 1L;

    private final DaoRegistry daos;
    private final BandejaDeNotificaciones bandeja;

    public ProveedorDeSuscripcion(DaoRegistry daos, BandejaDeNotificaciones bandeja) {
        this.daos = daos;
        this.bandeja = bandeja;
    }

    /**
     * {@code GET [base]/Subscription/{id}/$status}: cómo va esta suscripción.
     *
     * <p>Devuelve un {@code Bundle} de tipo {@code searchset} con un {@code SubscriptionStatus}
     * dentro, que es lo que dice la {@code OperationDefinition} de R5 — no el recurso a pelo.
     *
     * <p>⚠️ Aquí es donde sale el <strong>motivo</strong> de un fallo de entrega. En R4 vivía en
     * {@code Subscription.error}; en R5 ese elemento no existe y el motivo es
     * {@code SubscriptionStatus.error}, codificado contra {@code subscription-error}. La suscripción
     * en {@code error} dice <em>que</em> algo falló; esto dice <em>qué</em>.
     */
    @Operation(name = "$status", idempotent = true, type = Subscription.class)
    public Bundle estado(@IdParam IIdType identidad, RequestDetails peticion) {
        Subscription suscripcion = leer(identidad, peticion);
        String id = identidad.getIdPart();

        List<EventoDeNotificacion> fallidos = bandeja.fallidosDe(id);
        SubscriptionStatus estado = TraductorDeNotificacion.estado(
                suscripcion, List.of(), bandeja.eventosDe(id), SubscriptionNotificationType.QUERYSTATUS);

        fallidos.stream()
                .findFirst()
                .flatMap(EventoDeNotificacion::motivoDelFallo)
                .ifPresent(motivo -> estado.addError(TraductorDeNotificacion.motivoDelFallo("no-response", motivo)));

        Bundle respuesta = new Bundle();
        respuesta.setType(BundleType.SEARCHSET);
        respuesta.addEntry().setResource(estado);
        return respuesta;
    }

    /**
     * {@code GET [base]/Subscription/{id}/$events}: qué se ha notificado ya.
     *
     * <p>Es cómo un receptor que estuvo caído recupera lo que se perdió sin que el laboratorio tenga
     * que reintentar durante días. Devuelve un {@code Bundle} de notificación de verdad —del mismo
     * tipo y con la misma forma que el que salió por el canal—, así que el receptor lo procesa con el
     * mismo código.
     *
     * @param desde primer número de evento, inclusive
     * @param hasta último, inclusive
     */
    @Operation(name = "$events", idempotent = true, type = Subscription.class)
    public Bundle eventos(
            @IdParam IIdType identidad,
            @OperationParam(name = "eventsSinceNumber", max = 1) Integer64Type desde,
            @OperationParam(name = "eventsUntilNumber", max = 1) Integer64Type hasta,
            RequestDetails peticion) {
        Subscription suscripcion = leer(identidad, peticion);
        String id = identidad.getIdPart();
        long total = bandeja.eventosDe(id);

        long primero = desde == null || desde.getValue() == null ? DESDE_EL_PRINCIPIO : desde.getValue();
        long ultimo = hasta == null || hasta.getValue() == null ? Long.MAX_VALUE : hasta.getValue();
        if (primero > ultimo) {
            throw new DatoInvalido("`eventsSinceNumber` (%d) no puede ser mayor que `eventsUntilNumber` (%d)."
                    .formatted(primero, ultimo));
        }

        // El cuerpo se rearma con `id-only` igual que el original: `$events` no es una puerta trasera
        // por la que sacar los recursos completos de lo que ya se notificó sin ellos.
        return TraductorDeNotificacion.notificacion(
                suscripcion, bandeja.deLaSuscripcion(id, primero, ultimo), total, peticion.getFhirServerBase());
    }

    private Subscription leer(IIdType identidad, RequestDetails peticion) {
        return daos.getResourceDao(Subscription.class).read(identidad.toUnqualifiedVersionless(), peticion);
    }
}
