package es.hispalis.backend.infraestructura.edo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cómo declara este laboratorio a Salud Pública.
 *
 * @param habilitado si el notificador funciona. Encendido por defecto: apagar el cumplimiento de una
 *     obligación legal tiene que ser una decisión escrita, no un valor que falta.
 * @param destino la dirección del servicio de declaraciones. <strong>Vacía por defecto</strong>, y a
 *     propósito: el destinatario real depende del despliegue y no hay un valor sensato que poner. Sin
 *     destino, las obligaciones se registran igual y no salen — que es mejor que salir hacia una
 *     dirección adivinada.
 * @param destinatario el nombre del organismo, para darlo de alta como dato maestro
 * @param intervalo cada cuánto sale el notificador
 * @param tanda cuántos hechos y cuántas declaraciones se cogen por vuelta
 * @param tiempoDeEspera cuánto se espera al destinatario antes de dar el intento por perdido
 * @param intentos cuántas veces se intenta antes de dejar de intentarlo solo. <strong>El corte no
 *     borra la obligación:</strong> la declaración se queda abierta y, cuando pase el plazo, vencida.
 *     Lo que se corta es el bucle contra una puerta cerrada, que no declara nada y llena el log.
 */
@ConfigurationProperties(prefix = "hispalis.edo")
public record PropiedadesDelSvea(
        boolean habilitado,
        String destino,
        String destinatario,
        Duration intervalo,
        int tanda,
        Duration tiempoDeEspera,
        int intentos) {}
