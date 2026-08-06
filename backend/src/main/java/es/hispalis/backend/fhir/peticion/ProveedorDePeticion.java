package es.hispalis.backend.fhir.peticion;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.ServiceRequestResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.aplicacion.peticion.AnularLinea;
import es.hispalis.backend.aplicacion.peticion.RegistrarPeticion;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.springframework.stereotype.Component;

/** El proveedor de {@code ServiceRequest}: el de HAPI, con la escritura desviada al dominio. */
@Component
public class ProveedorDePeticion extends ServiceRequestResourceProvider implements ProveedorPropio {

    private final RegistrarPeticion registrarPeticion;
    private final AnularLinea anularLinea;

    public ProveedorDePeticion(RegistrarPeticion registrarPeticion, AnularLinea anularLinea) {
        this.registrarPeticion = registrarPeticion;
        this.anularLinea = anularLinea;
    }

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(ServiceRequest.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, ServiceRequest recibido, String condicional, RequestDetails detalles) {
        return registrarPeticion.ejecutar(recibido, detalles);
    }

    /**
     * {@inheritDoc}
     *
     * <p>De una línea ya registrada <strong>solo se puede anular</strong>, y de eso se encarga
     * {@link AnularLinea}, que además rechaza cualquier otra pretensión. El {@code update} heredado
     * de HAPI no se usa nunca: escribiría la proyección y dejaría el dominio atrás, en silencio.
     */
    @Override
    public MethodOutcome update(
            HttpServletRequest peticionHttp,
            ServiceRequest recibido,
            IIdType identidad,
            String condicional,
            RequestDetails detalles) {
        return anularLinea.ejecutar(identidad, recibido, detalles);
    }
}
