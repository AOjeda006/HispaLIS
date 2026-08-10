package es.hispalis.backend.fhir.resultado;

import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.dominio.resultado.CatalogoDeRangosDeReferencia;
import es.hispalis.backend.dominio.resultado.Disparo;
import es.hispalis.backend.dominio.resultado.Medicion;
import es.hispalis.backend.dominio.resultado.RangoDeReferencia;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.dominio.resultado.TipoDeDisparo;
import es.hispalis.backend.fhir.CatalogoDePruebas;
import es.hispalis.backend.fhir.PerfilesDeLaGuia;
import es.hispalis.backend.fhir.Referencias;
import es.hispalis.backend.fhir.ResultadosCualitativos;
import es.hispalis.backend.fhir.terminologia.Terminologia;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Observation.ObservationReferenceRangeComponent;
import org.hl7.fhir.r5.model.Observation.ObservationTriggeredByComponent;
import org.hl7.fhir.r5.model.Observation.TriggeredBytype;
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

    private final CatalogoDeRangosDeReferencia rangos;
    private final Terminologia terminologia;

    public TraductorDeResultado(CatalogoDeRangosDeReferencia rangos, Terminologia terminologia) {
        this.rangos = rangos;
        this.terminologia = terminologia;
    }

    /**
     * Construye el agregado a partir del recurso recibido.
     *
     * <p>Recibe el {@link Especimen} y la {@link Peticion} <strong>ya cargados del dominio</strong>,
     * no sus referencias: los invariantes —muestra rechazada, línea anulada— se comprueban dentro de
     * la fábrica del agregado, y para eso hacen falta los agregados de verdad, no lo que el recurso
     * diga de ellos. La línea es {@code null} cuando el resultado no viene de ningún volante.
     */
    public Resultado aDominio(Observation recurso, Especimen especimen, Peticion linea) {
        String codigo = codigoDelCatalogo(recurso);
        Medicion medicion = medicionDe(recurso);
        Disparo disparo = disparoDe(recurso, linea);

        if (recurso.hasValueQuantity()) {
            Quantity cantidad = recurso.getValueQuantity();
            return Resultado.informarCuantitativo(
                    especimen,
                    linea,
                    codigo,
                    cantidad.getValue(),
                    cantidad.hasCode() ? cantidad.getCode() : cantidad.getUnit(),
                    medicion,
                    disparo);
        }
        if (recurso.hasValueStringType()) {
            return Resultado.informarTextual(
                    especimen, linea, codigo, recurso.getValueStringType().getValue(), medicion, disparo);
        }
        if (recurso.hasValueCodeableConcept()) {
            CodeableConcept valor = recurso.getValueCodeableConcept();
            // ⚠️ El CÓDIGO manda sobre el `text`, y antes era al revés. Un `{coding:[{code:"POS"}],
            // text:"Positivo"}` se guardaba como la cadena «Positivo» y el código se perdía — con lo
            // que la regla de declaración obligatoria, que compara códigos, no habría visto nunca un
            // positivo. Solo cuando no viene ningún código se cae al texto: eso ya no es un resultado
            // codificado, es una descripción, y como tal se guarda.
            return ResultadosCualitativos.codigoDe(valor)
                    .map(codigoDelValor ->
                            Resultado.informarCualitativo(especimen, linea, codigo, codigoDelValor, medicion, disparo))
                    .orElseGet(() ->
                            Resultado.informarTextual(especimen, linea, codigo, valor.getText(), medicion, disparo));
        }
        throw new DatoInvalido(
                "El resultado no trae valor. Si no consta, hay que decirlo con `dataAbsentReason`, no dejarlo vacío.");
    }

    /** Genera el recurso publicable a partir del agregado. Esta es la proyección. */
    public Observation aFhir(Resultado resultado) {
        Observation recurso = new Observation();
        recurso.setId(resultado.id().toString());
        recurso.getMeta().addProfile(PerfilesDeLaGuia.RESULTADO_LAB.canonica());

        // `final` significa «revisado y definitivo», no «terminado de medir». Publicar como final lo
        // que solo ha pasado por el analizador es firmar por la máquina, y quien lo lee no tiene
        // forma de distinguirlo de un resultado revisado.
        recurso.setStatus(resultado.estaValidado() ? ObservationStatus.FINAL : ObservationStatus.PRELIMINARY);
        // El nombre en español y el LOINC equivalente los pone la terminología. Un `Observation` sin
        // LOINC solo lo entiende este laboratorio; con él lo entiende cualquiera que lo reciba.
        recurso.setCode(terminologia.pruebaDelCatalogo(resultado.codigoDePrueba()));
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
        // Un cualitativo sale CODIFICADO y con su nombre en español dentro. Publicar `POS` a secas
        // dejaría a la web y a la app enseñando un hueco: las dos leen `text` o `display`, que es lo
        // correcto —el código es para la máquina— y por eso el nombre tiene que venir puesto.
        resultado.valorCodificado().ifPresent(codigo -> recurso.setValue(terminologia.valorCualitativo(codigo)));

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

        // ⚠️ R5: `triggeredBy` no existe en R4. Es lo que permite decir, de forma procesable, que
        // esta determinación existe PORQUE otra salió alterada, se repitió o se re-ejecutó — y el
        // `reason` es lo que lo cuenta con palabras a quien lee el informe.
        resultado.disparadoPor().ifPresent(disparo -> recurso.addTriggeredBy()
                .setObservation(new Reference(disparo.referenciaDelOrigen()))
                .setType(TriggeredBytype.fromCode(disparo.tipo().codigoFhir()))
                .setReason(disparo.motivo()));
        return recurso;
    }

    /**
     * De dónde viene esta determinación, decidido en el orden en que manda el proyecto.
     *
     * <p><strong>{@code reflex} no lo declara el cliente.</strong> A qué prueba refleja cada prueba
     * es el protocolo del laboratorio y vive en su catálogo; admitirlo por la puerta de entrada
     * dejaría que quien manda un resultado se inventase un protocolo que el laboratorio no tiene, y
     * el {@code reason} publicado diría lo que él quisiera. Se rechaza con {@code 422}: el recurso
     * está bien formado, lo que incumple es una regla de negocio.
     *
     * <p>{@code repeat} y {@code re-run} sí, y tienen que serlo: la hemólisis del tubo y el control
     * de calidad del turno solo los ve quien repite. El laboratorio no tiene forma de deducirlos.
     *
     * <p>Cuando no viene nada declarado, el disparo sale de la <strong>línea</strong>: si la añadió
     * el laboratorio como refleja, el resultado que se informe contra ella hereda su origen y su
     * motivo sin que el cliente tenga que saber nada.
     */
    private static Disparo disparoDe(Observation recurso, Peticion linea) {
        if (recurso.hasTriggeredBy()) {
            ObservationTriggeredByComponent declarado = recurso.getTriggeredByFirstRep();
            TipoDeDisparo tipo = TipoDeDisparo.deCodigoFhir(
                    declarado.hasType() ? declarado.getType().toCode() : null);
            if (tipo == TipoDeDisparo.REFLEJA) {
                throw new ReglaDeNegocioIncumplida(
                        "Una prueba refleja la decide el laboratorio con la regla de su catálogo, no quien manda "
                                + "el resultado. Si esta determinación repite a otra, dilo con `repeat` o `re-run`.");
            }
            return new Disparo(
                    Referencias.identidadDe(declarado.getObservation(), "resultado que lo disparó"),
                    tipo,
                    declarado.hasReason() ? declarado.getReason() : null);
        }
        if (linea == null || linea.disparadaPor().isEmpty()) {
            return null;
        }
        return new Disparo(
                linea.disparadaPor().orElseThrow(),
                TipoDeDisparo.REFLEJA,
                linea.motivoDelDisparo().orElse(null));
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

    private String codigoDelCatalogo(Observation recurso) {
        String codigo = CatalogoDePruebas.codigoDe(recurso.getCode())
                .orElseThrow(() -> new DatoInvalido(
                        "El resultado tiene que decir qué prueba es, con un código del catálogo del laboratorio."));
        // La misma autoridad que valida la petición valida el resultado. Si el catálogo aceptase por
        // esta puerta lo que rechaza por la otra, publicaríamos un `Observation` intraducible a LOINC.
        terminologia.exigirQueLaPruebaExiste(codigo);
        return codigo;
    }
}
