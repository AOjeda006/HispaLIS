package es.hispalis.backend.infraestructura.auditoria;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.server.RestfulServer;
import es.hispalis.backend.fhir.auditoria.TraductorDeTraza;
import es.hispalis.backend.fhir.auditoria.TrazaDeAcceso;
import es.hispalis.backend.fhir.seguridad.QuienLlama;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La traza de acceso, detrás de {@code hispalis.auditoria.habilitada}.
 *
 * <p>Se registra como {@link SmartInitializingSingleton} y no como parámetro de la fábrica del
 * servidor FHIR, por la misma razón que la seguridad: el borde FHIR se construye igual exista o no
 * este {@code @Configuration}, y quien tiene que saber de los dos mundos es este fichero.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesDeAuditoria.class)
@ConditionalOnProperty(prefix = "hispalis.auditoria", name = "habilitada", havingValue = "true", matchIfMissing = true)
class ConfiguracionAuditoria {

    @Bean
    TraductorDeTraza traductorDeTraza(PropiedadesDeAuditoria propiedades) {
        return new TraductorDeTraza(propiedades.observador());
    }

    @Bean
    SmartInitializingSingleton registrarLaTrazaEnElServidorFhir(
            RestfulServer servidor,
            QuienLlama quienLlama,
            TraductorDeTraza traductor,
            DaoRegistry daos,
            FhirContext contexto) {
        return () -> servidor.registerInterceptor(new TrazaDeAcceso(quienLlama, traductor, daos, contexto));
    }
}
