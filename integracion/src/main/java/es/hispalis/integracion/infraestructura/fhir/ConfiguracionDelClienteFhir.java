package es.hispalis.integracion.infraestructura.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cableado del cliente con el que el motor escribe en el laboratorio. */
@Configuration
public class ConfiguracionDelClienteFhir {

    /** R5 y solo R5. El contexto es caro de construir y seguro de compartir: uno para todo el motor. */
    @Bean
    public FhirContext contextoFhir() {
        return FhirContext.forR5();
    }

    /**
     * @param url la base de la API del laboratorio, p. ej. {@code http://backend:8080/fhir}
     */
    @Bean
    public IGenericClient clienteFhir(
            FhirContext contexto,
            AutenticacionDelMotor autenticacion,
            @Value("${hispalis.laboratorio.url}") String url) {
        // Sin comprobación de conformidad al arrancar: el motor y el laboratorio se levantan a la vez
        // y el cliente se construye antes de que el otro esté listo. Pedirle el `CapabilityStatement`
        // aquí haría que el arranque dependiera del orden, con un fallo que parece de red.
        contexto.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);

        IGenericClient cliente = contexto.newRestfulGenericClient(url);
        cliente.registerInterceptor(autenticacion);
        return cliente;
    }
}
