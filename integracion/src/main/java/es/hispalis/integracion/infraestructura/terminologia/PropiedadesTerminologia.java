package es.hispalis.integracion.infraestructura.terminologia;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dónde está el servidor de terminología del que el motor saca el catálogo (D14).
 *
 * <p><strong>Una URL, y nada más.</strong> Ni tipo de servidor, ni operación propietaria, ni fichero
 * que montar: migrar a Snowstorm es cambiar esta línea.
 *
 * @param servidor base FHIR del servidor de terminología
 * @param tiempoDeEspera cuánto se le espera antes de dar el mensaje por no traducible
 */
@ConfigurationProperties(prefix = "hispalis.terminologia")
public record PropiedadesTerminologia(String servidor, Duration tiempoDeEspera) {

    public PropiedadesTerminologia {
        tiempoDeEspera = tiempoDeEspera == null ? Duration.ofSeconds(5) : tiempoDeEspera;
    }
}
