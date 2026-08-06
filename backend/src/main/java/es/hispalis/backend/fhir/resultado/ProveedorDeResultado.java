package es.hispalis.backend.fhir.resultado;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.rp.r5.ObservationResourceProvider;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.aplicacion.resultado.InformarResultado;
import es.hispalis.backend.aplicacion.resultado.ValidarResultado;
import es.hispalis.backend.fhir.EscrituraSoloPorAlta;
import es.hispalis.backend.fhir.ProveedorPropio;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Reference;
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
    private final ValidarResultado validarResultado;

    public ProveedorDeResultado(InformarResultado informarResultado, ValidarResultado validarResultado) {
        this.informarResultado = informarResultado;
        this.validarResultado = validarResultado;
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

    /** {@inheritDoc} Ver {@link EscrituraSoloPorAlta}: mejor un fallo visible que media escritura. */
    @Override
    public MethodOutcome update(
            HttpServletRequest peticionHttp,
            Observation recibido,
            IIdType identidad,
            String condicional,
            RequestDetails detalles) {
        throw EscrituraSoloPorAlta.rechazar("resultado");
    }

    /**
     * {@code POST /fhir/Observation/{id}/$validar}: la firma facultativa del resultado.
     *
     * <p>Es una operación y no un {@code PUT} porque el valor no cambia: cambia quién responde de él.
     * Ver {@link ValidarResultado}.
     *
     * <p>{@code facultativo} se declara opcional <strong>a propósito</strong>. Marcarlo obligatorio
     * aquí lo rechazaría con un mensaje genérico de HAPI sobre un parámetro que falta; dejándolo
     * pasar, quien responde es el dominio, que explica por qué hace falta saber quién valida. La
     * regla vive en un solo sitio y dice lo mismo entre por donde entre.
     */
    @Operation(name = "$validar", idempotent = false)
    public Observation validar(
            @IdParam IIdType identidad,
            @OperationParam(name = "facultativo", max = 1) Reference facultativo,
            @OperationParam(name = "cuando", max = 1) DateTimeType cuando,
            RequestDetails detalles) {
        return validarResultado.ejecutar(identidad, facultativo, cuando, detalles);
    }
}
