package es.hispalis.backend.infraestructura.terminologia;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dónde está el servidor de terminología y cuánto se le espera.
 *
 * <p><strong>Una URL, y nada más.</strong> Es lo que hace cierta D14: aquí no hay tipo de servidor,
 * ni credenciales de un producto concreto, ni una operación propietaria que configurar. Apuntar a
 * Snowstorm es cambiar esta línea.
 *
 * @param servidor base FHIR del servidor. Vacío = el laboratorio funciona sin terminología, que es
 *     el modo en que corren los tests que no van de esto
 * @param tiempoDeEspera cuánto se espera una respuesta antes de seguir sin ella
 */
@ConfigurationProperties(prefix = "hispalis.terminologia")
public record PropiedadesDeTerminologia(String servidor, Duration tiempoDeEspera) {

    public PropiedadesDeTerminologia {
        tiempoDeEspera = tiempoDeEspera == null ? Duration.ofSeconds(5) : tiempoDeEspera;
    }

    public boolean hayServidor() {
        return servidor != null && !servidor.isBlank();
    }
}
