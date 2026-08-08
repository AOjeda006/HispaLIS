package es.hispalis.integracion.infraestructura.seguridad;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /motor/jwks.json}: la parte pública de la clave del motor.
 *
 * <p><strong>Se publica por URL y no se pega en el <em>realm</em></strong>, y esa es la diferencia
 * entre poder rotar la clave y no poder. Con la clave copiada dentro de la configuración del cliente,
 * rotarla exige tocar el servidor de identidad en el mismo instante en que el motor cambia la suya —y
 * entre los dos momentos, el motor no puede escribir—. Bajándosela de aquí, el servidor de identidad
 * la relee cuando ve un {@code kid} que no conoce y la rotación se solapa sola.
 *
 * <p>Solo salen las claves públicas: {@link ClaveDelMotor} nunca deja salir la privada.
 */
@RestController
public class JwksDelMotor {

    private final ClaveDelMotor clave;

    public JwksDelMotor(ClaveDelMotor clave) {
        this.clave = clave;
    }

    @GetMapping(path = "/motor/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return clave.jwksPublico();
    }
}
