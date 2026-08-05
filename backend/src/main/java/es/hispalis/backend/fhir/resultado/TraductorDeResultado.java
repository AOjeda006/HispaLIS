package es.hispalis.backend.fhir.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.resultado.Medicion;
import es.hispalis.backend.dominio.resultado.RangoDeReferencia;
import es.hispalis.backend.dominio.resultado.RepositorioDeRangosDeReferencia;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Observation.ObservationReferenceRangeComponent;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.StringType;
import org.springframework.stereotype.Component;

/** La frontera entre el {@code Observation} de FHIR y el agregado {@link Resultado}. */
@Component
public class TraductorDeResultado {

    private static final String UCUM = "http://unitsofmeasure.org";
    private static final String SNOMED = "http://snomed.info/sct";

    /** Sexo al que aplica un rango, en SNOMED: los códigos que usa `referenceRange.appliesTo`. */
    private static final Map<String, String> SEXO_EN_SNOMED = Map.of("male", "248153007", "female", "248152002");

    private final RepositorioDeRangosDeReferencia rangos;

    public TraductorDeResultado(RepositorioDeRangosDeReferencia rangos) {
        this.rangos = rangos;
    }

    /**
     * Construye el agregado a partir del recurso recibido.
     *
     * <p>Recibe el {@link Especimen} ya cargado del dominio y no solo su referencia: el invariante de
     * C6 se comprueba dentro de la fábrica del agregado, y para eso hace falta la muestra de verdad,
     * no lo que el recurso diga de ella.
     */
    public Resultado aDominio(Observation recurso, Especimen especimen, UUID peticionId) {
        String codigo = codigoDelCatalogo(recurso);
        Medicion medicion = medicionDe(recurso);

        if (recurso.hasValueQuantity()) {
            Quantity cantidad = recurso.getValueQuantity();
            return Resultado.informarCuantitativo(
                    especimen,
                    peticionId,
                    codigo,
                    cantidad.getValue(),
                    cantidad.hasCode() ? cantidad.getCode() : cantidad.getUnit(),
                    medicion);
        }
        if (recurso.hasValueStringType()) {
            return Resultado.informarTextual(
                    especimen, peticionId, codigo, recurso.getValueStringType().getValue(), medicion);
        }
        if (recurso.hasValueCodeableConcept()) {
            CodeableConcept valor = recurso.getValueCodeableConcept();
            return Resultado.informarTextual(
                    especimen,
                    peticionId,
                    codigo,
                    valor.hasText()
                            ? valor.getText()
                            : valor.getCodingFirstRep().getCode(),
                    medicion);
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

        // `Must Support` no significa «obligatorio»: significa que si llega, el servidor lo guarda y
        // lo devuelve. Aquí se cierra esa segunda mitad.
        resultado
                .medicion()
                .realizadaEn()
                .ifPresent(cuando -> recurso.setEffective(new DateTimeType(Date.from(cuando))));
        resultado.medicion().realizadaPor().ifPresent(quien -> recurso.addPerformer(new Reference(quien)));

        // Sin rango, la cifra es un número suelto: «4,2» es normal para un potasio y alto para una
        // creatinina. Se publican TODOS los de la prueba, cada uno diciendo a quién aplica, porque
        // aquí no se conoce al paciente — y porque es para eso para lo que FHIR hizo `appliesTo`.
        rangos.buscarPorPrueba(resultado.codigoDePrueba()).forEach(rango -> recurso.addReferenceRange(aFhir(rango)));
        return recurso;
    }

    private static ObservationReferenceRangeComponent aFhir(RangoDeReferencia rango) {
        ObservationReferenceRangeComponent componente = new ObservationReferenceRangeComponent();
        componente.setLow(cantidad(rango.bajo(), rango.unidadUcum()));
        componente.setHigh(cantidad(rango.alto(), rango.unidadUcum()));
        rango.sexoAlQueAplica()
                .map(SEXO_EN_SNOMED::get)
                .ifPresent(codigo -> componente.addAppliesTo(new CodeableConcept()
                        .addCoding(new Coding().setSystem(SNOMED).setCode(codigo))));
        return componente;
    }

    private static Quantity cantidad(BigDecimal valor, String unidadUcum) {
        return new Quantity()
                .setValue(valor)
                .setUnit(unidadUcum)
                .setSystem(UCUM)
                .setCode(unidadUcum);
    }

    /**
     * Extrae de qué momento y de qué mano es el resultado.
     *
     * <p>{@code effective[x]} admite varios tipos y el perfil no cierra ninguno. Se aceptan los dos
     * que un laboratorio emite —un instante y un intervalo— y del intervalo se toma su
     * <strong>inicio</strong>, que es cuando empezó la determinación. Los demás tipos se ignoran en
     * vez de reventar: un {@code Timing} en un resultado de laboratorio no tiene sentido, y
     * rechazar el recurso entero por un elemento accesorio sería desproporcionado.
     */
    private static Medicion medicionDe(Observation recurso) {
        Instant cuando = null;
        if (recurso.hasEffectiveDateTimeType()) {
            cuando = recurso.getEffectiveDateTimeType().getValue().toInstant();
        } else if (recurso.hasEffectivePeriod() && recurso.getEffectivePeriod().hasStart()) {
            cuando = recurso.getEffectivePeriod().getStart().toInstant();
        }

        String quien = recurso.hasPerformer() ? recurso.getPerformerFirstRep().getReference() : null;
        return Medicion.de(cuando, quien);
    }

    private static String codigoDelCatalogo(Observation recurso) {
        return CatalogoDePruebas.codigoDe(recurso.getCode())
                .orElseThrow(() -> new DatoInvalido(
                        "El resultado tiene que decir qué prueba es, con un código del catálogo del laboratorio."));
    }
}
