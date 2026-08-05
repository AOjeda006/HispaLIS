package es.hispalis.backend.fhir.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import java.util.UUID;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.StringType;
import org.springframework.stereotype.Component;

/** La frontera entre el {@code Observation} de FHIR y el agregado {@link Resultado}. */
@Component
public class TraductorDeResultado {

    private static final String UCUM = "http://unitsofmeasure.org";

    /**
     * Construye el agregado a partir del recurso recibido.
     *
     * <p>Recibe el {@link Especimen} ya cargado del dominio y no solo su referencia: el invariante de
     * C6 se comprueba dentro de la fábrica del agregado, y para eso hace falta la muestra de verdad,
     * no lo que el recurso diga de ella.
     */
    public Resultado aDominio(Observation recurso, Especimen especimen, UUID peticionId) {
        String codigo = codigoDelCatalogo(recurso);

        if (recurso.hasValueQuantity()) {
            Quantity cantidad = recurso.getValueQuantity();
            return Resultado.informarCuantitativo(
                    especimen,
                    peticionId,
                    codigo,
                    cantidad.getValue(),
                    cantidad.hasCode() ? cantidad.getCode() : cantidad.getUnit());
        }
        if (recurso.hasValueStringType()) {
            return Resultado.informarTextual(
                    especimen, peticionId, codigo, recurso.getValueStringType().getValue());
        }
        if (recurso.hasValueCodeableConcept()) {
            CodeableConcept valor = recurso.getValueCodeableConcept();
            return Resultado.informarTextual(
                    especimen,
                    peticionId,
                    codigo,
                    valor.hasText()
                            ? valor.getText()
                            : valor.getCodingFirstRep().getCode());
        }
        throw new DatoInvalido(
                "El resultado no trae valor. Si no consta, hay que decirlo con `dataAbsentReason`, no dejarlo vacío.");
    }

    /** Genera el recurso publicable a partir del agregado. Esta es la proyección. */
    public Observation aFhir(Resultado resultado) {
        Observation recurso = new Observation();
        recurso.setId(resultado.id().toString());
        recurso.getMeta().addProfile(PerfilesDeLaGuia.RESULTADO_LAB.canonica());

        recurso.setStatus(ObservationStatus.FINAL);
        recurso.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDePruebas.SYSTEM).setCode(resultado.codigoDePrueba())));
        recurso.setSubject(new Reference("Patient/" + resultado.pacienteId()));
        recurso.setSpecimen(new Reference("Specimen/" + resultado.especimenId()));
        resultado.peticionId().ifPresent(peticion -> recurso.addBasedOn(new Reference("ServiceRequest/" + peticion)));

        resultado
                .valor()
                .ifPresent(valor -> recurso.setValue(new Quantity()
                        .setValue(valor)
                        // La unidad va dos veces a propósito: `unit` es lo que se imprime en el
                        // informe y `code` es lo que permite convertir y comparar.
                        .setUnit(resultado.unidadUcum().orElseThrow())
                        .setSystem(UCUM)
                        .setCode(resultado.unidadUcum().orElseThrow())));
        resultado.valorTextual().ifPresent(texto -> recurso.setValue(new StringType(texto)));
        return recurso;
    }

    private static String codigoDelCatalogo(Observation recurso) {
        return CatalogoDePruebas.codigoDe(recurso.getCode())
                .orElseThrow(() -> new DatoInvalido(
                        "El resultado tiene que decir qué prueba es, con un código del catálogo del laboratorio."));
    }
}
