package es.hispalis.backend.infraestructura.seguridad;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cómo se conecta el laboratorio con el servidor de identidad.
 *
 * @param habilitada si la API exige testigo. <strong>Encendida por defecto</strong>: apagarla tiene
 *     que ser una decisión que alguien escriba, y el arranque la avisa en voz alta. Es la misma regla
 *     que el TLS del motor
 * @param emisor la URL del <em>realm</em>, de la que cuelga {@code .well-known/openid-configuration}.
 *     Es lo único que se configura: los <em>endpoints</em> no se cablean, se descubren
 * @param audiencias las bases FHIR por las que se llega a este servidor. Un testigo cuyo {@code aud}
 *     no sea una de ellas se rechaza, aunque la firma sea buena y el emisor el correcto: es lo que
 *     impide que un testigo legítimo para otro servidor de recursos valga aquí
 * @param tiempoDeEspera cuánto se espera al servidor de identidad antes de darlo por caído
 */
@ConfigurationProperties(prefix = "hispalis.seguridad")
public record PropiedadesDeSeguridad(
        boolean habilitada, String emisor, List<String> audiencias, Duration tiempoDeEspera) {

    public PropiedadesDeSeguridad {
        audiencias = audiencias == null ? List.of() : List.copyOf(audiencias);
        tiempoDeEspera = tiempoDeEspera == null ? Duration.ofSeconds(5) : tiempoDeEspera;
    }

    public boolean hayEmisor() {
        return emisor != null && !emisor.isBlank();
    }
}
