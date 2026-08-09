package es.hispalis.backend.infraestructura.notificacion;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import es.hispalis.backend.fhir.notificacion.BandejaDeNotificaciones;
import es.hispalis.backend.fhir.notificacion.EventoDeNotificacion;
import es.hispalis.backend.fhir.notificacion.TraductorDeNotificacion;
import es.hispalis.backend.infraestructura.notificacion.EntregaFirmada.EntregaFallida;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Enumerations.SubscriptionStatusCodes;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Entrega lo que {@code AnotarLasNotificaciones} dejó apuntado, y corta cuando el receptor no está.
 *
 * <h2>Reintentos con corte</h2>
 *
 * <p>Cada notificación se intenta hasta {@code intentos} veces, con retroceso exponencial dentro de
 * la misma vuelta. Al agotarlos pasan <strong>dos</strong> cosas, y las dos importan:
 *
 * <ol>
 *   <li>la fila queda {@code FALLIDO} con su motivo, que es lo que después sale por {@code $status};
 *   <li>la {@code Subscription} pasa a {@code error}, y desde ese momento
 *       {@code AnotarLasNotificaciones} deja de anotarle nada.
 * </ol>
 *
 * <p>Eso segundo es el corte. Sin él, un receptor apagado el viernes se encuentra el lunes con miles
 * de notificaciones acumuladas y el laboratorio se habría pasado el fin de semana llamando a una
 * puerta cerrada. Reactivar es un acto explícito de alguien —un {@code PUT} devolviendo la
 * suscripción a {@code active}—, que es lo correcto: quien la reactive sabe que se ha perdido lo de
 * en medio, y {@code eventsSinceSubscriptionStart} le dice cuánto.
 *
 * <h2>Lo que NO hace</h2>
 *
 * <p>Reintentar sola una suscripción cortada, y no es una carencia: volver a intentarlo cada minuto
 * durante días es exactamente lo que el corte existe para evitar.
 */
public class RelayDeNotificaciones {

    private static final Logger LOG = LoggerFactory.getLogger(RelayDeNotificaciones.class);

    /** El nombre del parámetro de la `Subscription` que dice con qué clave se firma. Nunca la clave. */
    private static final String PARAMETRO_DE_CLAVE = "identificador-de-clave";

    private final BandejaDeNotificaciones bandeja;
    private final DaoRegistry daos;
    private final FhirContext contexto;
    private final EntregaFirmada entrega;
    private final PropiedadesDeNotificacion propiedades;

    RelayDeNotificaciones(
            BandejaDeNotificaciones bandeja,
            DaoRegistry daos,
            FhirContext contexto,
            EntregaFirmada entrega,
            PropiedadesDeNotificacion propiedades) {
        this.bandeja = bandeja;
        this.daos = daos;
        this.contexto = contexto;
        this.entrega = entrega;
        this.propiedades = propiedades;
    }

    /**
     * La vuelta periódica. Se traga lo que reviente <strong>a propósito</strong>: dejarlo subir solo
     * conseguiría que el planificador registrara la excepción y volviera a llamar igual.
     */
    @Scheduled(
            fixedDelayString = "${hispalis.notificaciones.intervalo:PT2S}",
            initialDelayString = "${hispalis.notificaciones.intervalo:PT2S}")
    void drenar() {
        try {
            drenarUnaVez();
        } catch (RuntimeException e) {
            LOG.warn("La vuelta del relay de notificaciones ha fallado entera; se reintenta. Causa: {}", e.toString());
        }
    }

    /**
     * Una tanda: se agrupa por suscripción y se manda una notificación por grupo.
     *
     * <p>Agrupar no es una optimización: {@code Subscription.maxCount} existe justamente para acotar
     * cuántos recursos caben en una notificación, y mandar una llamada por hecho convertiría una
     * validación de veinte determinaciones en veinte llamadas al mismo receptor.
     *
     * @return cuántas notificaciones se entregaron
     */
    int drenarUnaVez() {
        Map<String, List<EventoDeNotificacion>> porSuscripcion = new LinkedHashMap<>();
        for (EventoDeNotificacion pendiente : bandeja.pendientes(propiedades.tanda())) {
            porSuscripcion
                    .computeIfAbsent(pendiente.suscripcionId(), id -> new java.util.ArrayList<>())
                    .add(pendiente);
        }

        int entregadas = 0;
        for (Map.Entry<String, List<EventoDeNotificacion>> grupo : porSuscripcion.entrySet()) {
            if (entregarGrupo(grupo.getKey(), grupo.getValue())) {
                entregadas++;
            }
        }
        return entregadas;
    }

    private boolean entregarGrupo(String suscripcionId, List<EventoDeNotificacion> eventos) {
        Subscription suscripcion = leer(suscripcionId);
        if (suscripcion == null || suscripcion.getStatus() != SubscriptionStatusCodes.ACTIVE) {
            // La suscripción se borró o alguien la apagó mientras estas notificaciones esperaban.
            // No se entregan y no se reintentan: se cierran, para que el relay no las mire en cada
            // vuelta el resto de la vida del proceso.
            eventos.forEach(evento -> bandeja.marcarIntentoFallido(
                    evento.id(), "La suscripción ya no está activa cuando tocaba entregar.", true));
            return false;
        }

        List<EventoDeNotificacion> deEstaVuelta = acotar(eventos, suscripcion);
        Bundle notificacion = TraductorDeNotificacion.notificacion(
                suscripcion, deEstaVuelta, bandeja.eventosDe(suscripcionId), propiedades.baseFhir());
        String cuerpo = contexto.newJsonParser().encodeResourceToString(notificacion);

        String clave = identificadorDeClave(suscripcion);
        // Sin ninguna clave configurada, `secretos` llega nulo. Se resuelve a «no hay secreto para
        // esta suscripción», que es lo que `EntregaFirmada` sabe contar; un `NullPointerException`
        // aquí saldría como una vuelta del relay reventada y sin decir qué falta.
        String secreto =
                propiedades.secretos() == null ? null : propiedades.secretos().get(clave);

        EntregaFallida ultimoFallo = null;
        for (int intento = 1; intento <= propiedades.intentos(); intento++) {
            try {
                entrega.entregar(suscripcion.getEndpoint(), clave, secreto, cuerpo);
                Instant ahora = Instant.now();
                deEstaVuelta.forEach(evento -> bandeja.marcarEntregado(evento.id(), ahora));
                return true;
            } catch (EntregaFallida fallo) {
                ultimoFallo = fallo;
                deEstaVuelta.forEach(evento -> bandeja.marcarIntentoFallido(evento.id(), fallo.getMessage(), false));
                esperarAntesDelSiguiente(intento);
            }
        }

        cortar(suscripcion, deEstaVuelta, ultimoFallo);
        return false;
    }

    /**
     * Deja la suscripción en {@code error}, que es donde R5 quiere que se vea que algo va mal.
     *
     * <p>El motivo <strong>no cabe en la {@code Subscription}</strong>: R4 tenía
     * {@code Subscription.error} y R5 lo quitó. Va en la fila, y de ahí lo saca {@code $status} como
     * {@code SubscriptionStatus.error}. Escribir el estado aquí y el motivo allí no es un apaño — es
     * exactamente cómo lo modela el estándar.
     */
    private void cortar(Subscription suscripcion, List<EventoDeNotificacion> eventos, EntregaFallida fallo) {
        String motivo = fallo == null ? "Entrega fallida." : fallo.getMessage();
        String detalle = "%d intentos sin entregar. %s".formatted(propiedades.intentos(), motivo);

        eventos.forEach(evento -> bandeja.marcarIntentoFallido(evento.id(), detalle, true));

        suscripcion.setStatus(SubscriptionStatusCodes.ERROR);
        daos.getResourceDao(Subscription.class).update(suscripcion, new SystemRequestDetails());

        LOG.warn(
                "La suscripción {} pasa a `error` y deja de recibir: {}",
                suscripcion.getIdElement().getIdPart(),
                detalle);
    }

    /** {@code maxCount}: cuántos recursos caben como mucho en una notificación. Lo dice el suscriptor. */
    private static List<EventoDeNotificacion> acotar(List<EventoDeNotificacion> eventos, Subscription suscripcion) {
        int tope = suscripcion.hasMaxCount() ? suscripcion.getMaxCount() : eventos.size();
        return eventos.size() <= tope ? eventos : eventos.subList(0, tope);
    }

    private static String identificadorDeClave(Subscription suscripcion) {
        return suscripcion.getParameter().stream()
                .filter(parametro -> PARAMETRO_DE_CLAVE.equals(parametro.getName()))
                .map(Subscription.SubscriptionParameterComponent::getValue)
                .findFirst()
                .orElse("");
    }

    private Subscription leer(String suscripcionId) {
        try {
            return daos.getResourceDao(Subscription.class)
                    .read(new IdType("Subscription", suscripcionId), new SystemRequestDetails());
        } catch (RuntimeException noEsta) {
            return null;
        }
    }

    /** Retroceso exponencial. Sin él, cuatro intentos contra un receptor reiniciándose son cuatro fallos. */
    private void esperarAntesDelSiguiente(int intento) {
        try {
            Thread.sleep(propiedades.esperaEntreIntentos().toMillis() * (1L << (intento - 1)));
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
        }
    }
}
