package es.hispalis.backend.fhir.informe;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.DiagnosticReportResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.aplicacion.informe.EmitirInforme;
import es.hispalis.backend.fhir.EscrituraSoloPorAlta;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.springframework.stereotype.Component;

/** El proveedor de {@code DiagnosticReport}: el de HAPI, con la escritura desviada al dominio. */
@Component
public class ProveedorDeInforme extends DiagnosticReportResourceProvider implements ProveedorPropio {

    private final EmitirInforme emitirInforme;

    public ProveedorDeInforme(EmitirInforme emitirInforme) {
        this.emitirInforme = emitirInforme;
    }

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(DiagnosticReport.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, DiagnosticReport recibido, String condicional, RequestDetails detalles) {
        return emitirInforme.ejecutar(recibido, detalles);
    }

    /** {@inheritDoc} Ver {@link EscrituraSoloPorAlta}: mejor un fallo visible que media escritura. */
    @Override
    public MethodOutcome update(
            HttpServletRequest peticionHttp,
            DiagnosticReport recibido,
            IIdType identidad,
            String condicional,
            RequestDetails detalles) {
        throw EscrituraSoloPorAlta.rechazar("informe");
    }
}
