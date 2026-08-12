package es.hispalis.backend.fhir.auditoria;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.AuditEventResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.AuditEvent;
import org.springframework.stereotype.Component;

/**
 * El proveedor de {@code AuditEvent}: se lee, no se escribe.
 *
 * <p>Un registro de auditoría que el auditado puede escribir no es un registro de auditoría: es lo
 * que él quiera contar. La traza la levanta el servidor al atender la petición
 * ({@link TrazaDeAcceso}), y desde fuera no hay forma de añadir una, ni de corregir la que hay.
 *
 * <p>Es la misma regla que con {@code Provenance} y por la misma razón —lo que da fe de un acto lo
 * escribe quien presencia el acto—, con un matiz que la hace más estricta: una procedencia inventada
 * afirmaría algo que no pasó, y una traza inventada además <strong>taparía</strong> algo que sí pasó.
 *
 * <p>Se dejan abiertas la lectura y la búsqueda, que son las de HAPI sin tocar: sin ellas el registro
 * no serviría para nada. Quién puede consultarlas es asunto de los <em>scopes</em>
 * ({@code system/AuditEvent.rs}), no de este fichero.
 */
@Component
public class ProveedorDeTraza extends AuditEventResourceProvider implements ProveedorPropio {

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(AuditEvent.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, AuditEvent recibido, String condicional, RequestDetails detalles) {
        throw noSeEscribeDesdeFuera();
    }

    @Override
    public MethodOutcome update(
            HttpServletRequest peticionHttp,
            AuditEvent recibido,
            IIdType identidad,
            String condicional,
            RequestDetails detalles) {
        throw noSeEscribeDesdeFuera();
    }

    private static ReglaDeNegocioIncumplida noSeEscribeDesdeFuera() {
        throw new ReglaDeNegocioIncumplida(
                "La traza de acceso la levanta el servidor cuando atiende la petición, no el cliente: un "
                        + "`AuditEvent` enviado desde fuera contaría lo que su autor quiera y taparía lo que de "
                        + "verdad pasó. Las trazas se consultan con `GET /fhir/AuditEvent?…`.");
    }
}
