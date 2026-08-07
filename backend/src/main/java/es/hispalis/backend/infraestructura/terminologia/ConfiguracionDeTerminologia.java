package es.hispalis.backend.infraestructura.terminologia;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enchufa el servidor de terminología, si hay uno configurado. */
@Configuration
@EnableConfigurationProperties(PropiedadesDeTerminologia.class)
public class ConfiguracionDeTerminologia {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracionDeTerminologia.class);

    @Bean
    Terminologia terminologia(PropiedadesDeTerminologia propiedades, FhirContext contexto) {
        if (!propiedades.hayServidor()) {
            LOG.warn("Sin servidor de terminología (hispalis.terminologia.servidor está vacío): los recursos se "
                    + "publicarán con el código y sin nombre, y no se validará ninguna prueba.");
            return new SinServidorDeTerminologia();
        }
        LOG.info("Terminología resuelta contra {}", propiedades.servidor());
        return new TerminologiaDelServidor(clienteDe(propiedades, contexto));
    }

    private static IGenericClient clienteDe(PropiedadesDeTerminologia propiedades, FhirContext contexto) {
        // El contexto es el mismo que el del servidor y su configuración es global, así que se
        // toca solo lo del cliente: los tiempos de espera. Con los de fábrica —diez segundos para
        // conectar y otros diez para leer— una escritura del laboratorio se quedaría colgada veinte
        // segundos por un servidor de terminología que no está.
        contexto.getRestfulClientFactory()
                .setConnectTimeout((int) propiedades.tiempoDeEspera().toMillis());
        contexto.getRestfulClientFactory()
                .setSocketTimeout((int) propiedades.tiempoDeEspera().toMillis());
        // No se pide el `metadata` del otro servidor al construir el cliente: eso convertiría el
        // arranque del laboratorio en dependiente de que la terminología esté ya levantada.
        contexto.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        return contexto.newRestfulGenericClient(propiedades.servidor());
    }
}
