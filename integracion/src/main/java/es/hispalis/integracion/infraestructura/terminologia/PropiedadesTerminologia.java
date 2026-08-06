package es.hispalis.integracion.infraestructura.terminologia;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * De dónde saca el motor el catálogo de pruebas.
 *
 * @param directorio los recursos que produce SUSHI a partir del FSH de la guía
 */
@ConfigurationProperties(prefix = "hispalis.terminologia")
public record PropiedadesTerminologia(String directorio) {}
