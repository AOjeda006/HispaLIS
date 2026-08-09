package es.hispalis.backend.infraestructura.notificacion;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cómo entrega este laboratorio sus notificaciones de {@code Subscription}.
 *
 * @param habilitado si el relay sale a entregar. Encendido por defecto: que el laboratorio no avise
 *     de lo que ya ha anotado tiene que ser una decisión escrita, no un valor que falta.
 * @param baseFhir la base pública de esta API, la que el receptor puede resolver. No se deduce de la
 *     petición porque el relay no atiende ninguna: entrega desde un hilo de fondo.
 * @param intervalo cada cuánto sale el relay
 * @param tanda cuántas notificaciones se cogen por vuelta
 * @param tiempoDeEspera cuánto se espera al receptor antes de dar el intento por perdido
 * @param intentos cuántas veces se intenta antes de <strong>cortar</strong>. Al agotarlos, la
 *     {@code Subscription} pasa a {@code error} y deja de acumular trabajo: sin ese corte, un
 *     receptor apagado el viernes tiene el lunes miles de notificaciones y el laboratorio se ha
 *     pasado el fin de semana llamando a una puerta cerrada.
 * @param esperaEntreIntentos primera espera del retroceso, que se duplica en cada intento
 * @param secretos las claves compartidas con cada receptor, por identificador. <strong>Nunca van en
 *     el recurso {@code Subscription}</strong>: ese se lee por la API como cualquier otro, así que
 *     una credencial dentro estaría publicada a todo el que tenga permiso de lectura. La
 *     {@code Subscription} solo dice CUÁL se usa, en {@code parameter[identificador-de-clave]}.
 */
@ConfigurationProperties(prefix = "hispalis.notificaciones")
public record PropiedadesDeNotificacion(
        boolean habilitado,
        String baseFhir,
        Duration intervalo,
        int tanda,
        Duration tiempoDeEspera,
        int intentos,
        Duration esperaEntreIntentos,
        Map<String, String> secretos) {}
