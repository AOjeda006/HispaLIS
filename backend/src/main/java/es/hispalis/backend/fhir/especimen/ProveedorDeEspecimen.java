package es.hispalis.backend.fhir.especimen;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.SpecimenResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.aplicacion.especimen.RegistrarEspecimen;
import es.hispalis.backend.fhir.EscrituraSoloPorAlta;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Specimen;
import org.springframework.stereotype.Component;

/** El proveedor de {@code Specimen}: el de HAPI, con la escritura desviada al dominio. */
@Component
public class ProveedorDeEspecimen extends SpecimenResourceProvider implements ProveedorPropio {

    private final RegistrarEspecimen registrarEspecimen;

    public ProveedorDeEspecimen(RegistrarEspecimen registrarEspecimen) {
        this.registrarEspecimen = registrarEspecimen;
    }

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(Specimen.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, Specimen recibido, String condicional, RequestDetails detalles) {
        return registrarEspecimen.ejecutar(recibido, detalles);
    }

    /** {@inheritDoc} Ver {@link EscrituraSoloPorAlta}: mejor un fallo visible que media escritura. */
    @Override
    public MethodOutcome update(
            HttpServletRequest peticionHttp,
            Specimen recibido,
            IIdType identidad,
            String condicional,
            RequestDetails detalles) {
        throw EscrituraSoloPorAlta.rechazar("espécimen");
    }
}
