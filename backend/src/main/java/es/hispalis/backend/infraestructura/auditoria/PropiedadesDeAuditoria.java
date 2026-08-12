package es.hispalis.backend.infraestructura.auditoria;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La traza de acceso.
 *
 * @param habilitada si cada acceso a la API deja su {@code AuditEvent}. <strong>Encendida por
 *     defecto</strong>, como el resto de lo que responde a una obligación: apagar el registro de quién
 *     accede a datos de salud tiene que ser una decisión escrita, no un valor que falta
 * @param observador con qué nombre se identifica este servidor en las trazas que escribe. Sin él, una
 *     traza recogida de varios sistemas en un mismo SIEM no se puede atribuir a ninguno
 */
@ConfigurationProperties(prefix = "hispalis.auditoria")
public record PropiedadesDeAuditoria(boolean habilitada, String observador) {}
