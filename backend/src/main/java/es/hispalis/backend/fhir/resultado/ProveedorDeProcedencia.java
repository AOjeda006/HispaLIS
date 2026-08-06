package es.hispalis.backend.fhir.resultado;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.ProvenanceResourceProvider;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Provenance;
import org.springframework.stereotype.Component;

/**
 * El proveedor de {@code Provenance}: se lee, no se escribe.
 *
 * <p>Un {@code Provenance} de este laboratorio <strong>da fe de un acto propio</strong> — que un
 * facultativo firmó un resultado —, así que lo genera quien presencia el acto: el caso de uso que lo
 * ejecuta, dentro de su misma transacción. Un cliente que pudiera crearlo estaría certificando una
 * validación que no ha ocurrido, y el rastro de quién responde de cada cifra dejaría de ser rastro
 * para ser declaración de parte.
 *
 * <p>Por eso las dos puertas de escritura se cierran, en vez de dejarlas heredadas y abiertas. Es la
 * regla de {@code ADR-0014}: lo heredado se revisa antes de darlo por bueno, y lo que no tiene reglas
 * de negocio definidas se rechaza. La lectura y la búsqueda —{@code ?target=Observation/…}, que es
 * como se consulta quién validó— siguen siendo las de HAPI, sin tocar.
 */
@Component
public class ProveedorDeProcedencia extends ProvenanceResourceProvider implements ProveedorPropio {

    @Override
    public void enlazarConSuDao(DaoRegistry daos) {
        setDao(daos.getResourceDao(Provenance.class));
    }

    @Override
    public MethodOutcome create(
            HttpServletRequest peticionHttp, Provenance recibido, String condicional, RequestDetails detalles) {
        throw noSeEscribeDesdeFuera();
    }

    @Override
    public MethodOutcome update(
            HttpServletRequest peticionHttp,
            Provenance recibido,
            IIdType identidad,
            String condicional,
            RequestDetails detalles) {
        throw noSeEscribeDesdeFuera();
    }

    private static ReglaDeNegocioIncumplida noSeEscribeDesdeFuera() {
        throw new ReglaDeNegocioIncumplida(
                "La procedencia la escribe el laboratorio cuando el acto ocurre, no el cliente: un `Provenance` "
                        + "enviado desde fuera certificaría algo que aquí no ha pasado. Para dejar constancia de "
                        + "una validación, valida el resultado con `POST /fhir/Observation/{id}/$validar`.");
    }
}
