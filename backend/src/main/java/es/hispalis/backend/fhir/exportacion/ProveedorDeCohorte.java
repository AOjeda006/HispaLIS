package es.hispalis.backend.fhir.exportacion;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.GroupResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Group;
import org.springframework.stereotype.Component;

/**
 * El proveedor de {@code Group}: se lee y se busca, no se escribe.
 *
 * <p>La IG de Bulk Data describe tres patrones de gestión de grupos y pide elegir uno. Este servidor
 * elige el <strong>de solo lectura</strong>: las cohortes las abre el laboratorio al declarar una
 * enfermedad obligatoria, y crecen solas con cada declaración.
 *
 * <p>Cerrar la escritura es lo que hace que la exportación tenga sentido. Si un cliente pudiera
 * componer el {@code Group}, podría meter en él a quien quisiera y exportar su compartimento — es
 * decir, el <em>scope</em> «puedo exportar cohortes» se convertiría en «puedo exportar a cualquiera»,
 * que no es lo que nadie concedió.
 *
 * <p>La búsqueda sí queda abierta, y no es un descuido: la propia IG lo pide —«si soportas exportación
 * de grupo, soporta también leer y buscar {@code Group}»— porque es como un cliente descubre las
 * cohortes por su {@code identifier} sin depender de ids técnicos.
 */
@Component
public class ProveedorDeCohorte extends GroupResourceProvider implements ProveedorPropio {

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(Group.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, Group recibido, String condicional, RequestDetails detalles) {
        throw noSeEscribeDesdeFuera();
    }

    @Override
    public MethodOutcome update(
            HttpServletRequest peticionHttp,
            Group recibido,
            IIdType identidad,
            String condicional,
            RequestDetails detalles) {
        throw noSeEscribeDesdeFuera();
    }

    private static ReglaDeNegocioIncumplida noSeEscribeDesdeFuera() {
        throw new ReglaDeNegocioIncumplida(
                "Las cohortes de vigilancia las abre el laboratorio al declarar una enfermedad obligatoria, no el "
                        + "cliente: una cohorte compuesta desde fuera permitiría exportar a quien la compuso le "
                        + "apeteciera. Se consultan con `GET /fhir/Group?identifier=…`.");
    }
}
