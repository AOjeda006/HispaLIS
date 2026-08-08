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
 *     Es el {@code iss} que llevan los testigos y lo que se publica a los clientes: los
 *     <em>endpoints</em> no se cablean, se descubren
 * @param interno por dónde alcanza <strong>este servidor</strong> al de identidad, si no es por el
 *     mismo sitio que el navegador. Vacío casi siempre; en la pila de desarrollo, no. El emisor que
 *     firma Keycloak es {@code http://localhost:8081/realms/hispalis} porque es lo que el navegador
 *     tiene que ver, y dentro de un contenedor {@code localhost} es el propio contenedor. Un
 *     {@code extra_hosts} no lo arregla: {@code /etc/hosts} ya trae {@code 127.0.0.1 localhost} en
 *     la primera línea y gana. Con esto, el laboratorio <strong>valida</strong> contra el emisor
 *     público y <strong>va a buscar</strong> el descubrimiento y el JWKS por la red interna
 * @param audiencias las bases FHIR por las que se llega a este servidor. Un testigo cuyo {@code aud}
 *     no sea una de ellas se rechaza, aunque la firma sea buena y el emisor el correcto: es lo que
 *     impide que un testigo legítimo para otro servidor de recursos valga aquí
 * @param tiempoDeEspera cuánto se espera al servidor de identidad antes de darlo por caído
 */
@ConfigurationProperties(prefix = "hispalis.seguridad")
public record PropiedadesDeSeguridad(
        boolean habilitada, String emisor, String interno, List<String> audiencias, Duration tiempoDeEspera) {

    public PropiedadesDeSeguridad {
        audiencias = audiencias == null ? List.of() : List.copyOf(audiencias);
        tiempoDeEspera = tiempoDeEspera == null ? Duration.ofSeconds(5) : tiempoDeEspera;
    }

    public boolean hayEmisor() {
        return emisor != null && !emisor.isBlank();
    }

    /** El emisor sin barras finales: es el prefijo con el que se comparan las URL descubiertas. */
    public String emisorNormalizado() {
        return emisor == null ? null : emisor.replaceAll("/+$", "");
    }

    /** Por dónde va este servidor a buscar; el emisor público si no se ha configurado otra cosa. */
    public String baseInterna() {
        return interno == null || interno.isBlank() ? emisorNormalizado() : interno.replaceAll("/+$", "");
    }

    /**
     * Traduce una URL anunciada por el servidor de identidad a la que este servidor puede alcanzar.
     *
     * <p>Solo se traduce lo que empieza por el emisor público. Una URL que apunte a otro sitio se
     * deja como está: no es de este servidor de identidad y reescribirla sería mandar el tráfico a
     * donde nadie ha dicho.
     */
    public String alcanzable(String anunciada) {
        String publico = emisorNormalizado();
        if (anunciada == null || publico == null || !anunciada.startsWith(publico)) {
            return anunciada;
        }
        return baseInterna() + anunciada.substring(publico.length());
    }
}
