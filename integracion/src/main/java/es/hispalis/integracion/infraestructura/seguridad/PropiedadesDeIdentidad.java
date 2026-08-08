package es.hispalis.integracion.infraestructura.seguridad;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cómo se identifica el motor ante el laboratorio (D5, SMART Backend Services).
 *
 * @param habilitada si el motor pide testigo antes de escribir. <strong>Encendida por defecto</strong>:
 *     apagarla solo vale contra un laboratorio que tampoco exige testigo, y el arranque lo avisa
 * @param emisor la URL del <em>realm</em>. Es lo único que se configura del servidor de identidad: el
 *     {@code token_endpoint} no se cablea, se descubre
 * @param cliente el {@code client_id} con el que este motor está dado de alta
 * @param scopes los {@code system/} que pide. Se piden explícitamente y no «todos»: el testigo que se
 *     obtiene es el que se va a usar, y pedir de más es conceder de más a quien robe el testigo
 * @param clavePrivada la clave RSA con la que se firma la aserción, en PKCS#8 y base64.
 *     <strong>Llega por variable de entorno</strong>; nunca está en el repositorio. Vacía en
 *     desarrollo: se genera una efímera y el arranque lo dice en voz alta
 * @param margen cuánto antes de caducar se renueva el testigo. Sin margen, una petición lanzada un
 *     instante antes del vencimiento llega con un testigo muerto
 * @param tiempoDeEspera cuánto se espera al servidor de identidad
 */
@ConfigurationProperties(prefix = "hispalis.identidad")
public record PropiedadesDeIdentidad(
        boolean habilitada,
        String emisor,
        String cliente,
        String scopes,
        String clavePrivada,
        Duration margen,
        Duration tiempoDeEspera) {

    public PropiedadesDeIdentidad {
        margen = margen == null ? Duration.ofSeconds(30) : margen;
        tiempoDeEspera = tiempoDeEspera == null ? Duration.ofSeconds(5) : tiempoDeEspera;
    }

    public boolean hayClavePropia() {
        return clavePrivada != null && !clavePrivada.isBlank();
    }
}
