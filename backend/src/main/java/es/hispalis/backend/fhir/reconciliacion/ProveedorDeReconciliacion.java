package es.hispalis.backend.fhir.reconciliacion;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import es.hispalis.backend.aplicacion.reconciliacion.Divergencia;
import es.hispalis.backend.aplicacion.reconciliacion.InformeDeReconciliacion;
import es.hispalis.backend.aplicacion.reconciliacion.Reconciliador;
import es.hispalis.backend.fhir.Referencias;
import java.util.UUID;
import org.hl7.fhir.r5.model.BooleanType;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.IntegerType;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.StringType;
import org.springframework.stereotype.Component;

/**
 * {@code POST /fhir/$reconciliar}: la vía de recuperación de §15, publicada donde se puede usar.
 *
 * <p>Va en la API FHIR y no en un guion suelto porque §15 la pide como <strong>vía oficial</strong>,
 * y una vía oficial se ejecuta desde donde se administra el sistema, aparece en el
 * {@code CapabilityStatement} y responde con un recurso que se puede archivar. Un {@code .sh} en el
 * portátil de alguien no cumple ninguna de las tres.
 *
 * <p><strong>Por defecto no escribe.</strong> {@code aplicar} viene a falso si no se dice lo
 * contrario: la orden que se teclea con prisa a las tres de la mañana tiene que ser la que solo mira.
 *
 * <p>La respuesta lleva <strong>referencias, no diferencias</strong>. Ver {@link Divergencia}.
 */
@Component
public class ProveedorDeReconciliacion {

    private final Reconciliador reconciliador;

    public ProveedorDeReconciliacion(Reconciliador reconciliador) {
        this.reconciliador = reconciliador;
    }

    /**
     * @param paciente acota el recorrido a una persona; sin él, el laboratorio entero
     * @param aplicar si además de comparar hay que reparar
     */
    @Operation(name = "$reconciliar", idempotent = false)
    public Parameters reconciliar(
            @OperationParam(name = "paciente", max = 1) Reference paciente,
            @OperationParam(name = "aplicar", max = 1) BooleanType aplicar) {
        UUID soloEstePaciente =
                paciente == null || paciente.isEmpty() ? null : Referencias.identidadDe(paciente, "paciente");
        boolean escribir = aplicar != null && Boolean.TRUE.equals(aplicar.getValue());

        return aFhir(reconciliador.ejecutar(soloEstePaciente, escribir));
    }

    private static Parameters aFhir(InformeDeReconciliacion informe) {
        Parameters respuesta = new Parameters();
        respuesta.addParameter().setName("aplicado").setValue(new BooleanType(informe.aplicado()));
        respuesta
                .addParameter()
                .setName("divergencias")
                .setValue(new IntegerType(informe.divergencias().size()));

        for (Divergencia divergencia : informe.divergencias()) {
            Parameters.ParametersParameterComponent parametro =
                    respuesta.addParameter().setName("divergencia");
            parametro.addPart().setName("recurso").setValue(new StringType(divergencia.referencia()));
            parametro
                    .addPart()
                    .setName("clase")
                    .setValue(new CodeType(divergencia.clase().name().toLowerCase()));
        }
        return respuesta;
    }
}
