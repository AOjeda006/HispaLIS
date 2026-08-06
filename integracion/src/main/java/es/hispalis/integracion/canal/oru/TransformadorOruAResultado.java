package es.hispalis.integracion.canal.oru;

import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.v251.datatype.CE;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
import ca.uhn.hl7v2.model.v251.segment.OBX;
import es.hispalis.integracion.hl7.Campos;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import es.hispalis.integracion.terminologia.PruebaDelCatalogo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Quantity;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.StringType;
import org.springframework.stereotype.Component;

/**
 * El transformador del canal de resultados: {@code OBX} de un {@code ORU^R01} → {@code Observation}.
 *
 * <p>Aquí se juegan tres cosas que un mapeo ingenuo se salta, y las tres tienen consecuencia clínica.
 *
 * <h2>{@code OBX-2} dice de qué tipo es el valor, y hay que hacerle caso</h2>
 *
 * <p>{@code NM} es un número, {@code ST} es texto y {@code CE} es un concepto codificado. Dar por
 * hecho que todo resultado es una cifra funciona hasta el primer cultivo, y entonces
 * {@code Double.parseDouble("Negativo")} revienta el canal entero por un resultado perfectamente
 * normal.
 *
 * <h2>{@code OBX-11} no es {@code Observation.status} sin traducir</h2>
 *
 * <p>Aunque casi coincidan de nombre, no significan lo mismo: el {@code F} de v2 quiere decir
 * «resultado final del <em>analizador</em>», no «revisado por un facultativo». Todo lo que entra por
 * este canal entra <strong>preliminar</strong>, y lo pone el laboratorio, no el mensaje: el
 * analizador mide, no valida (ítem 18). Lo que sí se mira de {@code OBX-11} es si es una
 * <strong>corrección</strong> o un resultado anulado, que son casos que el laboratorio no debe
 * tragarse como si fueran nuevos.
 *
 * <h2>La unidad la manda el catálogo, no el mensaje</h2>
 *
 * <p>Si {@code OBX-6} no coincide con la unidad en que el laboratorio publica esa prueba, el
 * resultado <strong>no entra</strong>. Una creatinina en {@code umol/L} guardada como si fuera
 * {@code mg/dL} multiplica la cifra por 88 y la deja dentro de un rango que no es el suyo — y no hay
 * nada en el recurso que permita notarlo después.
 *
 * <h2>El analizador no cabe en {@code Observation.performer}</h2>
 *
 * <p>Y no es un detalle de tipos: en R5 ese elemento admite {@code Practitioner},
 * {@code PractitionerRole}, {@code Organization}, {@code CareTeam}, {@code Patient} y
 * {@code RelatedPerson} — <strong>{@code Device} no</strong>. El sitio del aparato es
 * {@code Observation.device}, y para apuntar ahí el laboratorio tendría que tener registrado su
 * inventario de analizadores como recursos {@code Device}, que hoy no lo tiene: la referencia se
 * rechazaría por integridad referencial y con ella el resultado entero.
 *
 * <p>Así que <strong>este canal no rellena {@code performer}</strong>. La identidad del analizador no
 * se pierde por eso: el mensaje original se guarda íntegro y es él el registro de auditoría. Queda
 * pendiente modelar el inventario y entonces poblar {@code Observation.device} — anotado en
 * {@code docs/PLAN.md}, no aquí.
 */
@Component
public class TransformadorOruAResultado {

    private static final String UCUM = "http://unitsofmeasure.org";

    /** {@code OBX-11}: los estados que este laboratorio sabe aplicar. */
    private static final String CORREGIDO = "C";

    private static final String ANULADO = "X";
    private static final String NO_DISPONIBLE_TODAVIA = "I";

    private final CatalogoDelLaboratorio catalogo;

    public TransformadorOruAResultado(CatalogoDelLaboratorio catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * Los resultados que trae el mensaje, en orden.
     *
     * @throws ResultadoInaceptable si alguno no se puede traducir o no cuadra con el catálogo
     */
    public List<ResultadoMedido> resultados(ORU_R01 mensaje) {
        List<ResultadoMedido> medidos = new ArrayList<>();
        for (ORU_R01_ORDER_OBSERVATION orden : ordenes(mensaje)) {
            for (int i = 0; i < orden.getOBSERVATIONReps(); i++) {
                medidos.add(leer(orden.getOBSERVATION(i).getOBX()));
            }
        }
        if (medidos.isEmpty()) {
            throw new ResultadoInaceptable("El ORU^R01 no trae ningún OBX: no hay resultado que informar.");
        }
        return medidos;
    }

    /**
     * El número de acceso de la muestra sobre la que se midió.
     *
     * <p>Se busca en {@code SPM-2} y, si el analizador no manda {@code SPM} —muchos no lo hacen—, en
     * {@code OBR-3} (<em>filler order number</em>), que es donde suele ir la etiqueta del tubo.
     */
    public Optional<String> numeroDeAcceso(ORU_R01 mensaje) {
        for (ORU_R01_ORDER_OBSERVATION orden : ordenes(mensaje)) {
            for (int i = 0; i < orden.getSPECIMENReps(); i++) {
                Optional<String> desdeSpm = Campos.opcional(orden.getSPECIMEN(i)
                        .getSPM()
                        .getSpecimenID()
                        .getFillerAssignedIdentifier()
                        .getEntityIdentifier());
                if (desdeSpm.isPresent()) {
                    return desdeSpm;
                }
                Optional<String> puestoPorElPeticionario = Campos.opcional(orden.getSPECIMEN(i)
                        .getSPM()
                        .getSpecimenID()
                        .getPlacerAssignedIdentifier()
                        .getEntityIdentifier());
                if (puestoPorElPeticionario.isPresent()) {
                    return puestoPorElPeticionario;
                }
            }
            Optional<String> desdeObr =
                    Campos.opcional(orden.getOBR().getFillerOrderNumber().getEntityIdentifier());
            if (desdeObr.isPresent()) {
                return desdeObr;
            }
        }
        return Optional.empty();
    }

    /** El número de volante, de {@code ORC-4} o {@code OBR-2}, si el analizador lo devuelve. */
    public Optional<String> numeroDeVolante(ORU_R01 mensaje) {
        for (ORU_R01_ORDER_OBSERVATION orden : ordenes(mensaje)) {
            Optional<String> desdeOrc =
                    Campos.opcional(orden.getORC().getPlacerGroupNumber().getEntityIdentifier());
            if (desdeOrc.isPresent()) {
                return desdeOrc;
            }
            Optional<String> desdeObr =
                    Campos.opcional(orden.getOBR().getPlacerOrderNumber().getEntityIdentifier());
            if (desdeObr.isPresent()) {
                return desdeObr;
            }
        }
        return Optional.empty();
    }

    /**
     * El recurso que se enviará a la API.
     *
     * @param medido lo que dijo el analizador
     * @param pacienteRef {@code Patient/<id>}
     * @param especimenRef {@code Specimen/<id>}
     * @param lineaRef {@code ServiceRequest/<id>}, o {@code null} si el resultado no viene de volante
     */
    public Observation aObservation(ResultadoMedido medido, String pacienteRef, String especimenRef, String lineaRef) {
        Observation recurso = new Observation();

        // Preliminar SIEMPRE. Ver la nota de la clase: el `F` de OBX-11 es del analizador.
        recurso.setStatus(ObservationStatus.PRELIMINARY);
        recurso.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDelLaboratorio.SYSTEM).setCode(medido.codigoDePrueba())));
        recurso.setSubject(new Reference(pacienteRef));
        recurso.setSpecimen(new Reference(especimenRef));
        if (lineaRef != null) {
            recurso.addBasedOn(new Reference(lineaRef));
        }

        medido.cifra()
                .ifPresent(valor -> recurso.setValue(new Quantity()
                        .setValue(valor)
                        .setUnit(medido.unidadUcum())
                        .setSystem(UCUM)
                        .setCode(medido.unidadUcum())));
        medido.texto().ifPresent(texto -> recurso.setValue(new StringType(texto)));

        // De cuándo es la cifra. `Must Support` en el perfil: si llega, se guarda.
        medido.medidoEn().ifPresent(cuando -> recurso.setEffective(new DateTimeType(Date.from(cuando))));
        return recurso;
    }

    private ResultadoMedido leer(OBX obx) {
        String estado = Campos.texto(obx.getObservationResultStatus());
        if (ANULADO.equals(estado)) {
            throw new ResultadoInaceptable(("El analizador manda OBX-11=X (resultado anulado). Anular un resultado ya "
                    + "informado es un acto clínico con reglas propias y todavía no está soportado: se "
                    + "rechaza en vez de escribir la mitad."));
        }
        if (NO_DISPONIBLE_TODAVIA.equals(estado)) {
            throw new ResultadoInaceptable(
                    "El analizador manda OBX-11=I (resultado pedido pero aún no disponible). No hay cifra que guardar.");
        }
        if (CORREGIDO.equals(estado)) {
            throw new ResultadoInaceptable(("El analizador manda OBX-11=C (corrección de un resultado anterior). "
                    + "Rectificar un resultado ya emitido es otra operación clínica —¿se sustituye?, ¿se "
                    + "marca enmendado?, ¿qué pasa con el informe que lo incluía?— y no se improvisa."));
        }

        CE identificador = obx.getObservationIdentifier();
        String codigoRecibido = Campos.texto(identificador.getIdentifier());
        String codigoLocal = catalogo.traducirALocal(
                        systemDe(Campos.texto(identificador.getNameOfCodingSystem())), codigoRecibido)
                .orElseThrow(() -> new ResultadoInaceptable(("La prueba «%s» que informa OBX-3 no está en el catálogo "
                                + "del laboratorio ni tiene equivalencia desde LOINC.")
                        .formatted(codigoRecibido)));

        PruebaDelCatalogo prueba = catalogo.buscar(codigoLocal)
                .orElseThrow(() -> new ResultadoInaceptable(
                        "«%s» se tradujo al catálogo pero no está en él.".formatted(codigoLocal)));

        String tipo = Campos.texto(obx.getValueType());
        String valor = valorDe(obx);
        Optional<Instant> cuando =
                Campos.instante(Campos.texto(obx.getDateTimeOfTheObservation().getTime()));

        if ("NM".equals(tipo)) {
            return new ResultadoMedido(
                    codigoLocal,
                    Optional.of(cifra(valor, codigoLocal)),
                    Optional.empty(),
                    unidadQueCuadre(obx, prueba),
                    cuando);
        }
        if ("ST".equals(tipo) || "TX".equals(tipo) || "CE".equals(tipo) || "CWE".equals(tipo)) {
            if (valor.isBlank()) {
                throw new ResultadoInaceptable("El resultado de «%s» viene sin valor en OBX-5.".formatted(codigoLocal));
            }
            return new ResultadoMedido(codigoLocal, Optional.empty(), Optional.of(valor), null, cuando);
        }
        throw new ResultadoInaceptable(("El tipo de valor «%s» de OBX-2 no está soportado en este canal. Se rechaza en "
                        + "vez de adivinar cómo se guarda.")
                .formatted(tipo));
    }

    /**
     * Comprueba {@code OBX-6} contra la unidad del catálogo.
     *
     * <p>Un {@code OBX-6} vacío se acepta y se usa la del catálogo: es lo que declara el laboratorio
     * para esa prueba, y muchos analizadores no rellenan el campo. Uno que venga <strong>y no
     * coincida</strong> se rechaza, que es el caso peligroso.
     */
    private static String unidadQueCuadre(OBX obx, PruebaDelCatalogo prueba) {
        String delCatalogo = prueba.unidad()
                .orElseThrow(() -> new ResultadoInaceptable(("«%s» es una prueba cualitativa en el catálogo del "
                                + "laboratorio y el analizador la informa con una cifra (OBX-2=NM).")
                        .formatted(prueba.codigo())));

        String declarada = Campos.texto(obx.getUnits().getIdentifier());
        if (!declarada.isBlank() && !declarada.equals(delCatalogo)) {
            throw new ResultadoInaceptable(("El analizador informa «%s» en «%s» y este laboratorio publica esa prueba "
                            + "en «%s». No se convierte a ojo: se rechaza.")
                    .formatted(prueba.codigo(), declarada, delCatalogo));
        }
        return delCatalogo;
    }

    private static BigDecimal cifra(String valor, String codigo) {
        try {
            // La coma decimal es lo que manda un analizador configurado en español. v2 dice punto,
            // pero rechazar el resultado por eso sería tirar una cifra buena por un separador.
            return new BigDecimal(valor.replace(',', '.'));
        } catch (NumberFormatException noEsUnNumero) {
            throw new ResultadoInaceptable(
                    "El resultado de «%s» dice ser numérico (OBX-2=NM) y trae «%s».".formatted(codigo, valor));
        }
    }

    private static String valorDe(OBX obx) {
        if (obx.getObservationValueReps() == 0) {
            return "";
        }
        Varies valor = obx.getObservationValue(0);
        return valor.getData() == null ? "" : valor.getData().toString().strip();
    }

    private static String systemDe(String nombreEnV2) {
        return switch (nombreEnV2.toUpperCase()) {
            case "LN" -> CatalogoDelLaboratorio.LOINC;
            case "99HISPALIS", "L" -> CatalogoDelLaboratorio.SYSTEM;
            default -> null;
        };
    }

    private static List<ORU_R01_ORDER_OBSERVATION> ordenes(ORU_R01 mensaje) {
        List<ORU_R01_ORDER_OBSERVATION> grupos = new ArrayList<>();
        for (int i = 0; i < mensaje.getPATIENT_RESULTReps(); i++) {
            var resultado = mensaje.getPATIENT_RESULT(i);
            for (int j = 0; j < resultado.getORDER_OBSERVATIONReps(); j++) {
                grupos.add(resultado.getORDER_OBSERVATION(j));
            }
        }
        return grupos;
    }

    /**
     * Lo que el analizador midió, ya traducido y comprobado.
     *
     * @param codigoDePrueba código del catálogo local
     * @param cifra el valor numérico, si la prueba es cuantitativa
     * @param texto el valor textual, si es cualitativa
     * @param unidadUcum la unidad del catálogo; {@code null} en las cualitativas
     * @param medidoEn {@code OBX-14}
     */
    public record ResultadoMedido(
            String codigoDePrueba,
            Optional<BigDecimal> cifra,
            Optional<String> texto,
            String unidadUcum,
            Optional<Instant> medidoEn) {}

    /** El resultado no se puede aplicar tal y como viene. */
    public static class ResultadoInaceptable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ResultadoInaceptable(String mensaje) {
            super(mensaje);
        }
    }
}
