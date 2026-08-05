package es.hispalis.backend.fhir.paciente;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.PatientResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.aplicacion.paciente.AltaDePaciente;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Component;

/**
 * El proveedor de {@code Patient}: el de HAPI, con la escritura desviada al dominio.
 *
 * <p>Hereda de {@link PatientResourceProvider} en vez de escribirse desde cero, y no por pereza: de
 * ahí vienen la lectura, la búsqueda por {@code SearchParameter}, {@code _include}, la paginación
 * por {@code Bundle.link}, {@code _history}, {@code vread} y el {@code ETag}/{@code If-Match}, que
 * son justo los criterios de aceptación 8, 10 y 11. Reimplementarlos sería rehacer HAPI peor.
 *
 * <p>Lo único que cambia es <strong>la creación</strong>, porque es el único punto donde la
 * arquitectura difiere: un {@code POST} no escribe el recurso que llega, sino que lo traduce a un
 * alta de dominio y publica lo que el dominio produce (D3, §9). El resto de operaciones leen de la
 * proyección, que es exactamente para lo que existe.
 *
 * <p>Este proveedor <strong>sustituye</strong> al que fabrica HAPI: registrar dos para el mismo
 * recurso es un error de arranque, así que el servidor filtra el suyo (ver
 * {@code ConfiguracionServidorFhir}).
 */
@Component
public class ProveedorDePaciente extends PatientResourceProvider implements ProveedorPropio {

    private final AltaDePaciente altaDePaciente;

    public ProveedorDePaciente(AltaDePaciente altaDePaciente) {
        this.altaDePaciente = altaDePaciente;
    }

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(Patient.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, Patient recibido, String condicional, RequestDetails detalles) {
        return altaDePaciente.ejecutar(recibido, detalles);
    }
}
