package es.hispalis.backend.fhir.resultado;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.ObservationResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.aplicacion.resultado.InformarResultado;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r5.model.Observation;
import org.springframework.stereotype.Component;

/**
 * El proveedor de {@code Observation}: el de HAPI, con la escritura desviada al dominio.
 *
 * <p>Es la puerta por la que se aplica el invariante de C6. Desviar <em>la escritura</em> y no la
 * lectura es lo correcto: consultar un resultado ya emitido no puede romper ninguna regla, y
 * hacerlo pasar por el dominio solo añadiría un mapeo inútil en el camino caliente.
 */
@Component
public class ProveedorDeResultado extends ObservationResourceProvider implements ProveedorPropio {

    private final InformarResultado informarResultado;

    public ProveedorDeResultado(InformarResultado informarResultado) {
        this.informarResultado = informarResultado;
    }

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(Observation.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, Observation recibido, String condicional, RequestDetails detalles) {
        return informarResultado.ejecutar(recibido, detalles);
    }
}
