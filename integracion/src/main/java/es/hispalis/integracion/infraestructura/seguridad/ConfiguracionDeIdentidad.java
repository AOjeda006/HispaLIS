package es.hispalis.integracion.infraestructura.seguridad;

import es.hispalis.integracion.infraestructura.fhir.AutenticacionDelMotor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El cableado de la identidad del motor.
 *
 * <p>El interruptor {@code hispalis.identidad.habilitada} va <strong>encendido por defecto</strong>,
 * igual que el TLS del MLLP y por la misma razón: escribir en un laboratorio clínico sin identificarse
 * tiene que ser una decisión que alguien escriba, no un valor que falta.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesDeIdentidad.class)
public class ConfiguracionDeIdentidad {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionDeIdentidad.class);

    @Bean
    public ClaveDelMotor claveDelMotor(PropiedadesDeIdentidad propiedades) {
        return new ClaveDelMotor(propiedades);
    }

    @Bean
    public TestigoDeSistema testigoDeSistema(PropiedadesDeIdentidad propiedades, ClaveDelMotor clave) {
        return new TestigoDeSistema(propiedades, clave);
    }

    @Bean
    public AutenticacionDelMotor autenticacionDelMotor(PropiedadesDeIdentidad propiedades, TestigoDeSistema testigos) {
        if (!propiedades.habilitada()) {
            log.warn("⚠️  IDENTIDAD DESACTIVADA (hispalis.identidad.habilitada=false): el motor escribe en el "
                    + "laboratorio sin testigo. Solo vale contra un laboratorio que tampoco lo exija.");
            return new AutenticacionDelMotor.SinIdentidad();
        }
        if (propiedades.emisor() == null || propiedades.emisor().isBlank()) {
            throw new IllegalStateException("La identidad del motor está activada pero no hay emisor: configura "
                    + "hispalis.identidad.emisor (HISPALIS_OIDC_EMISOR) con la URL del realm, o apágala a "
                    + "conciencia con hispalis.identidad.habilitada=false.");
        }
        if (propiedades.cliente() == null || propiedades.cliente().isBlank()) {
            throw new IllegalStateException("La identidad del motor está activada pero no hay client_id: configura "
                    + "hispalis.identidad.cliente (HISPALIS_MOTOR_CLIENTE).");
        }
        return new AutenticacionDelMotor.PorBackendServices(testigos);
    }
}
