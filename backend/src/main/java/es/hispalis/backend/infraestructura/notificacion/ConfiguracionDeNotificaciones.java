package es.hispalis.backend.infraestructura.notificacion;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import es.hispalis.backend.fhir.notificacion.BandejaDeNotificaciones;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * La entrega de notificaciones: cliente firmado y relay.
 *
 * <p>Todo cuelga de {@code hispalis.notificaciones.habilitado}, como el bus. Con la entrega apagada
 * no se crea el relay y <strong>las notificaciones se siguen anotando</strong>: eso es a propósito,
 * porque es lo que permite que un test compruebe qué se anotó sin montar un receptor, y porque
 * apagar la salida no puede cambiar lo que el laboratorio considera notificable.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PropiedadesDeNotificacion.class)
@ConditionalOnProperty(
        prefix = "hispalis.notificaciones",
        name = "habilitado",
        havingValue = "true",
        matchIfMissing = true)
class ConfiguracionDeNotificaciones {

    @Bean
    EntregaFirmada entregaFirmada(PropiedadesDeNotificacion propiedades) {
        return new EntregaFirmada(propiedades.tiempoDeEspera());
    }

    @Bean
    RelayDeNotificaciones relayDeNotificaciones(
            BandejaDeNotificaciones bandeja,
            DaoRegistry daos,
            FhirContext contexto,
            EntregaFirmada entrega,
            PropiedadesDeNotificacion propiedades) {
        return new RelayDeNotificaciones(bandeja, daos, contexto, entrega, propiedades);
    }
}
