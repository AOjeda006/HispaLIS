package es.hispalis.integracion.infraestructura.terminologia;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enchufa el catálogo del motor al servidor de terminología. */
@Configuration
@EnableConfigurationProperties(PropiedadesTerminologia.class)
public class ConfiguracionDeTerminologia {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracionDeTerminologia.class);

    @Bean
    public CatalogoDelLaboratorio catalogo(PropiedadesTerminologia propiedades, FhirContext contexto) {
        LOG.info("El catálogo del laboratorio se resuelve contra {}", propiedades.servidor());
        return new CatalogoDelServidorDeTerminologia(clienteDe(propiedades, contexto));
    }

    /**
     * Cuenta las pruebas al arrancar y lo dice en voz alta.
     *
     * <p>Un servidor de terminología vacío no hace fallar nada al levantarse: el motor arranca, acepta
     * mensajes y los manda todos a la bandeja de errores porque no traduce ninguno. Sin este aviso, el
     * síntoma sería «el HIS dice que nada entra» horas después. Se hace <strong>al estar listo</strong>
     * y no al construir el bean para que el motor no dependa del orden de arranque del {@code compose}.
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> avisoSiElCatalogoVieneVacio(CatalogoDelLaboratorio catalogo) {
        return listo -> {
            int pruebas = catalogo.tamano();
            if (pruebas == 0) {
                LOG.warn("El servidor de terminología no ofrece ni una prueba: mientras siga así, el motor no podrá "
                        + "traducir ningún OBR-4 ni OBX-3 y todo mensaje acabará en la bandeja de errores. "
                        + "Revisa que el cargador de terminología haya terminado.");
            } else {
                LOG.info("El catálogo del laboratorio ofrece {} pruebas", pruebas);
            }
        };
    }

    private static IGenericClient clienteDe(PropiedadesTerminologia propiedades, FhirContext contexto) {
        // El contexto es el mismo que el del cliente del laboratorio y su configuración es global: se
        // toca aquí porque los dos quieren lo mismo —no pedir el `CapabilityStatement` al construir el
        // cliente— y porque el arranque del motor no puede depender de quién se levante antes.
        contexto.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        contexto.getRestfulClientFactory()
                .setConnectTimeout((int) propiedades.tiempoDeEspera().toMillis());
        contexto.getRestfulClientFactory()
                .setSocketTimeout((int) propiedades.tiempoDeEspera().toMillis());
        return contexto.newRestfulGenericClient(propiedades.servidor());
    }
}
