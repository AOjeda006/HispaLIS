package es.hispalis.integracion.mllp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dónde escucha el HIS lo que el laboratorio le manda.
 *
 * @param servidor dónde está su listener MLLP
 * @param puerto en qué puerto
 * @param tls si se habla cifrado; encendido por defecto, como el lado receptor
 * @param verificarCertificado si se exige un certificado de confianza; apagado en desarrollo, donde
 *     el HIS presenta un autofirmado
 * @param aplicacion {@code MSH-3} con el que este laboratorio se presenta
 * @param instalacion {@code MSH-4}
 * @param aplicacionDestino {@code MSH-5}
 * @param instalacionDestino {@code MSH-6}
 */
@ConfigurationProperties(prefix = "hispalis.his")
public record PropiedadesDelHis(
        String servidor,
        int puerto,
        boolean tls,
        boolean verificarCertificado,
        String aplicacion,
        String instalacion,
        String aplicacionDestino,
        String instalacionDestino) {}
