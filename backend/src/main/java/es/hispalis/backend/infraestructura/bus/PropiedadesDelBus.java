package es.hispalis.backend.infraestructura.bus;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Los ajustes del relay. Lo del broker en sí va por {@code spring.kafka.*}, que ya es estándar.
 *
 * @param habilitado si el relay corre. Encendido por defecto: apagarlo es una decisión que hay que
 *     tomar, no un descuido de configuración. Los tests que no van del bus lo apagan explícitamente.
 * @param registroDeEsquemas URL del registro. Es la misma que se le pasa al serializador, y se
 *     declara aquí una sola vez para que no puedan discrepar.
 * @param intervalo cada cuánto se mira el {@code outbox}
 * @param tanda cuántos hechos como mucho por vuelta. Acotado a propósito: una cola sin límite es
 *     memoria sin límite el día que el bus lleve horas caído y se acumule el pendiente.
 * @param esperaDeEnvio cuánto se espera el acuse de cada hecho antes de dejarlo para la vuelta
 *     siguiente
 */
@ConfigurationProperties("hispalis.bus")
public record PropiedadesDelBus(
        boolean habilitado, String registroDeEsquemas, Duration intervalo, int tanda, Duration esperaDeEnvio) {}
