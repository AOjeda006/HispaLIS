package es.hispalis.integracion.canal.oml;

import ca.uhn.hl7v2.model.v251.datatype.CE;
import ca.uhn.hl7v2.model.v251.datatype.CWE;
import ca.uhn.hl7v2.model.v251.group.OML_O21_ORDER;
import ca.uhn.hl7v2.model.v251.message.OML_O21;
import ca.uhn.hl7v2.model.v251.segment.OBR;
import ca.uhn.hl7v2.model.v251.segment.ORC;
import ca.uhn.hl7v2.model.v251.segment.SPM;
import es.hispalis.integracion.hl7.Campos;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.CodeableReference;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations.RequestIntent;
import org.hl7.fhir.r5.model.Enumerations.RequestStatus;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;
import org.springframework.stereotype.Component;

/**
 * El transformador del canal de peticiones: {@code OML^O21} → líneas de volante y muestras.
 *
 * <p>Devuelve <strong>listas de recursos</strong> y no un {@code Bundle}, y eso es D22 hecho código:
 * el motor escribe recurso a recurso contra la API, así que lo que el transformador produce es
 * exactamente la secuencia de escrituras que el canal va a intentar, en orden.
 *
 * <h2>De dónde sale el número de volante</h2>
 *
 * <p>De {@code ORC-4} (<em>placer group number</em>), que es el campo que existe precisamente para
 * agrupar las órdenes que se pidieron juntas. Es el equivalente exacto de
 * {@code ServiceRequest.requisition}, y por eso <strong>el apaño de la web —generar el número en el
 * cliente— no aplica a este camino</strong>: si el HIS emite el número, manda el suyo.
 *
 * <h2>Los códigos no se traducen con una tabla de aquí dentro</h2>
 *
 * <p>{@code OBR-4} y {@code SPM-4} pasan por {@link CatalogoDelLaboratorio}, que lee el
 * {@code CodeSystem}, el {@code ConceptMap} y el {@code ValueSet} que publica la guía. Un
 * {@code Map<String,String>} en esta clase sería la lista paralela que el proyecto prohíbe
 * (invariante 4).
 */
@Component
public class TransformadorOmlAPeticion {

    private static final String SNOMED = "http://snomed.info/sct";

    private final CatalogoDelLaboratorio catalogo;

    public TransformadorOmlAPeticion(CatalogoDelLaboratorio catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * Las líneas que pide el mensaje, una por grupo {@code ORDER}.
     *
     * @throws PeticionIncompleta si falta el número de volante, el peticionario, o si alguna prueba
     *     no se puede traducir al catálogo del laboratorio
     */
    public List<LineaPedida> lineas(OML_O21 mensaje) {
        String volante = numeroDeVolante(mensaje);
        String peticionario = peticionario(mensaje);

        // `LinkedHashMap` y no una lista: un HIS que repite la misma prueba en dos grupos `ORDER` del
        // mismo volante está pidiendo una vez, no dos, y el laboratorio no tiene forma de distinguir
        // las dos líneas después. Se conserva el orden de llegada, que es el del volante en papel.
        Map<String, LineaPedida> porPrueba = new LinkedHashMap<>();
        for (OML_O21_ORDER orden : ordenes(mensaje)) {
            OBR obr = orden.getOBSERVATION_REQUEST().getOBR();
            String codigo = traducirPrueba(obr.getUniversalServiceIdentifier());
            porPrueba.putIfAbsent(codigo, new LineaPedida(volante, codigo, peticionario, cuandoSePidio(orden, obr)));
        }
        if (porPrueba.isEmpty()) {
            throw new PeticionIncompleta("El OML^O21 no pide ninguna prueba: no trae ningún grupo ORDER con OBR-4.");
        }
        return List.copyOf(porPrueba.values());
    }

    /**
     * Las muestras que anuncia el mensaje.
     *
     * <p>El mismo tubo puede aparecer colgando de varios {@code OBR} —es lo normal cuando tres pruebas
     * se hacen sobre la misma sangre—, así que se deduplica por número de acceso. Sin esto, un volante
     * de tres bioquímicas registraría tres veces la misma muestra.
     */
    public List<MuestraAnunciada> muestras(OML_O21 mensaje) {
        Map<String, MuestraAnunciada> porAcceso = new LinkedHashMap<>();
        for (OML_O21_ORDER orden : ordenes(mensaje)) {
            var peticionDeObservacion = orden.getOBSERVATION_REQUEST();
            for (int i = 0; i < peticionDeObservacion.getSPECIMENReps(); i++) {
                SPM spm = peticionDeObservacion.getSPECIMEN(i).getSPM();
                String acceso = Campos.texto(
                        spm.getSpecimenID().getPlacerAssignedIdentifier().getEntityIdentifier());
                if (acceso.isBlank()) {
                    throw new PeticionIncompleta(
                            "Una muestra del mensaje no trae número de acceso en SPM-2: sin él no hay forma de unir "
                                    + "el tubo con su resultado.");
                }
                porAcceso.putIfAbsent(acceso, new MuestraAnunciada(acceso, tipoDeMuestra(spm.getSpecimenType())));
            }
        }
        return List.copyOf(porAcceso.values());
    }

    /** El recurso que se enviará a la API por cada línea. */
    public ServiceRequest aServiceRequest(LineaPedida linea, String pacienteRef) {
        ServiceRequest recurso = new ServiceRequest();
        recurso.setStatus(RequestStatus.ACTIVE);
        recurso.setIntent(RequestIntent.ORDER);
        recurso.getRequisition().setValue(linea.numeroDeVolante());
        recurso.setSubject(new Reference(pacienteRef));
        recurso.setRequester(new Reference(linea.peticionario()));
        linea.solicitadaEn().ifPresent(cuando -> recurso.setAuthoredOn(Date.from(cuando)));

        // ⚠️ R5: `ServiceRequest.code` es `CodeableReference`, no `CodeableConcept`. Copiar un
        // ejemplo de R4 produce aquí un JSON que el servidor rechaza sin mencionar la versión.
        recurso.setCode(new CodeableReference(new CodeableConcept()
                .addCoding(new Coding().setSystem(CatalogoDelLaboratorio.SYSTEM).setCode(linea.codigoDePrueba()))));
        return recurso;
    }

    /** El recurso que se enviará a la API por cada muestra. */
    public Specimen aSpecimen(MuestraAnunciada muestra, String pacienteRef) {
        Specimen recurso = new Specimen();
        recurso.getAccessionIdentifier().setValue(muestra.numeroDeAcceso());
        recurso.setSubject(new Reference(pacienteRef));
        recurso.setType(
                new CodeableConcept().addCoding(new Coding().setSystem(SNOMED).setCode(muestra.tipo())));

        // El estado lo decide el laboratorio al recepcionar el tubo, no el HIS al anunciarlo. Se
        // registra disponible: rechazar una muestra es un acto del laboratorio con su motivo, y
        // dejar que el emisor lo declare pondría el invariante C6 en manos de quien manda el mensaje.
        recurso.setStatus(Specimen.SpecimenStatus.AVAILABLE);
        return recurso;
    }

    private String numeroDeVolante(OML_O21 mensaje) {
        for (OML_O21_ORDER orden : ordenes(mensaje)) {
            String grupo = Campos.texto(orden.getORC().getPlacerGroupNumber().getEntityIdentifier());
            if (!grupo.isBlank()) {
                return grupo;
            }
        }
        throw new PeticionIncompleta(
                "El OML^O21 no trae número de volante en ORC-4 (placer group number). Es lo que agrupa las líneas "
                        + "de una misma petición, y sin él cada prueba quedaría suelta.");
    }

    /**
     * Quién pide, de {@code ORC-12} (o de {@code OBR-16} si el emisor lo pone ahí).
     *
     * <p>Se envía como {@code Practitioner/<id>} con el identificador que manda el HIS. Si ese
     * facultativo no está en el directorio del laboratorio, <strong>la API lo rechaza y el mensaje
     * acaba en la DLQ</strong> — que es la conducta correcta: el motor no da de alta profesionales
     * por su cuenta, y reprocesar el mensaje después de darlo de alta lo aplica entero.
     */
    private static String peticionario(OML_O21 mensaje) {
        for (OML_O21_ORDER orden : ordenes(mensaje)) {
            ORC orc = orden.getORC();
            String desdeOrc = Campos.texto(orc.getOrderingProvider(0).getIDNumber());
            if (!desdeOrc.isBlank()) {
                return "Practitioner/" + desdeOrc;
            }
            String desdeObr = Campos.texto(orden.getOBSERVATION_REQUEST()
                    .getOBR()
                    .getOrderingProvider(0)
                    .getIDNumber());
            if (!desdeObr.isBlank()) {
                return "Practitioner/" + desdeObr;
            }
        }
        throw new PeticionIncompleta(
                "El OML^O21 no dice quién pide las pruebas (ORC-12 ni OBR-16). El laboratorio necesita saber a quién "
                        + "devolver el resultado.");
    }

    private String traducirPrueba(CE identificador) {
        String codigo = Campos.texto(identificador.getIdentifier());
        String system = systemDe(Campos.texto(identificador.getNameOfCodingSystem()));
        return catalogo.traducirALocal(system, codigo)
                .orElseThrow(() -> new PeticionIncompleta(
                        ("La prueba «%s» que pide OBR-4 no está en el catálogo del laboratorio ni tiene equivalencia "
                                        + "desde LOINC. No se traduce a ojo: se rechaza.")
                                .formatted(codigo)));
    }

    private String tipoDeMuestra(CWE tipo) {
        String codigo = Campos.texto(tipo.getIdentifier());
        if (codigo.isBlank()) {
            throw new PeticionIncompleta("Una muestra del mensaje no dice de qué tipo es (SPM-4).");
        }
        if (!catalogo.esTipoDeMuestraConocido(codigo)) {
            throw new PeticionIncompleta(("El tipo de muestra «%s» de SPM-4 no está entre los que acepta este "
                            + "laboratorio, según el ValueSet que publica la guía.")
                    .formatted(codigo));
        }
        return codigo;
    }

    /**
     * Traduce el nombre del sistema de codificación de la tabla 0396 a su URI FHIR.
     *
     * <p>Son los dos únicos que este laboratorio entiende. No es una tabla de terminología —no
     * traduce ningún concepto—: es la correspondencia entre cómo se nombra un catálogo en v2 y cómo
     * se nombra en FHIR, que no está en ningún {@code ConceptMap} y no puede estarlo.
     */
    private static String systemDe(String nombreEnV2) {
        return switch (nombreEnV2.toUpperCase()) {
            case "LN" -> CatalogoDelLaboratorio.LOINC;
            case "99HISPALIS", "L" -> CatalogoDelLaboratorio.SYSTEM;
            // Vacío: el emisor no lo dice. El catálogo prueba los dos, empezando por el propio.
            default -> null;
        };
    }

    /**
     * Los grupos {@code ORDER} del mensaje.
     *
     * <p>Se recorren por índice y no con {@code getORDERAll()}: ese lanza {@code HL7Exception}
     * comprobada, y envolverla en cada uso ensuciaría los tres sitios donde se recorre para no ganar
     * nada — el número de repeticiones ya lo sabe el modelo.
     */
    private static List<OML_O21_ORDER> ordenes(OML_O21 mensaje) {
        List<OML_O21_ORDER> grupos = new ArrayList<>(mensaje.getORDERReps());
        for (int i = 0; i < mensaje.getORDERReps(); i++) {
            grupos.add(mensaje.getORDER(i));
        }
        return grupos;
    }

    private static Optional<Instant> cuandoSePidio(OML_O21_ORDER orden, OBR obr) {
        List<String> candidatos = new ArrayList<>();
        candidatos.add(Campos.texto(orden.getORC().getDateTimeOfTransaction().getTime()));
        candidatos.add(Campos.texto(obr.getObservationDateTime().getTime()));
        return candidatos.stream().filter(valor -> !valor.isBlank()).findFirst().flatMap(Campos::instante);
    }

    /**
     * Una línea del volante, tal y como la pide el mensaje.
     *
     * @param numeroDeVolante {@code ORC-4}
     * @param codigoDePrueba ya traducido al catálogo del laboratorio
     * @param peticionario {@code Practitioner/<id>}
     * @param solicitadaEn cuándo se pidió, si el mensaje lo dice
     */
    public record LineaPedida(
            String numeroDeVolante, String codigoDePrueba, String peticionario, Optional<Instant> solicitadaEn) {}

    /**
     * Una muestra anunciada por el mensaje.
     *
     * @param numeroDeAcceso {@code SPM-2}
     * @param tipo {@code SPM-4}, código SNOMED del {@code ValueSet} de la guía
     */
    public record MuestraAnunciada(String numeroDeAcceso, String tipo) {}

    /** Al mensaje le falta algo que el laboratorio exige, o trae algo que no sabe traducir. */
    public static class PeticionIncompleta extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public PeticionIncompleta(String mensaje) {
            super(mensaje);
        }
    }
}
