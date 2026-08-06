package es.hispalis.integracion.saliente;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.v251.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v251.message.ORU_R01;
import ca.uhn.hl7v2.model.v251.segment.OBX;
import ca.uhn.hl7v2.model.v251.segment.PID;
import es.hispalis.integracion.fhir.SistemasDeIdentificador;
import es.hispalis.integracion.terminologia.CatalogoDelLaboratorio;
import es.hispalis.integracion.terminologia.PruebaDelCatalogo;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Enumerations.ObservationStatus;
import org.hl7.fhir.r5.model.HumanName;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Component;

/**
 * Construye el {@code ORU^R01} que sale hacia el HIS cuando el laboratorio emite un informe.
 *
 * <h2>Los códigos salen en el lenguaje común, no en el dialecto</h2>
 *
 * <p>Hacia dentro el motor traduce LOINC → catálogo local; hacia fuera hace lo contrario, y con el
 * <strong>mismo</strong> {@code ConceptMap} leído en su dirección natural. Cuando una prueba no tiene
 * equivalencia LOINC, {@code OBX-3} sale con el código local y su {@code system} propio en
 * {@code OBX-3.3}: mandar un LOINC inventado sería peor que mandar un código que el receptor no
 * conozca, porque el receptor no tendría forma de saber que es inventado.
 *
 * <h2>Solo salen resultados validados</h2>
 *
 * <p>Un informe emitido solo contiene resultados firmados —lo garantiza el núcleo del laboratorio
 * (ítem 18)—, pero aquí se vuelve a comprobar antes de escribir cada {@code OBX}. No es desconfianza
 * del backend: es que este mensaje sale del sistema, y un preliminar publicado como {@code F} hacia
 * el HIS es un resultado que alguien va a leer como definitivo sin que nadie haya respondido de él.
 */
@Component
public class TransformadorInformeAOru {

    private static final DateTimeFormatter SELLO_V2 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId ZONA = ZoneId.of("Europe/Madrid");

    /** {@code MSH-18}. Latín-1 es lo que entiende un HIS español; los apellidos viajan con su eñe. */
    private static final String CHARSET = "8859/1";

    private final CatalogoDelLaboratorio catalogo;

    public TransformadorInformeAOru(CatalogoDelLaboratorio catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * El mensaje que se enviará al HIS.
     *
     * @param informe el {@code DiagnosticReport} emitido
     * @param resultados los {@code Observation} que cita, ya leídos
     * @param paciente el sujeto del informe
     * @param emisor {@code MSH-3}: cómo se llama este laboratorio para el HIS
     * @param instalacion {@code MSH-4}
     * @param destino {@code MSH-5}
     * @param instalacionDestino {@code MSH-6}
     * @param controlId {@code MSH-10}, único por mensaje
     * @throws HL7Exception si el modelo no se deja componer
     * @throws InformeNoPublicable si el informe no trae nada que se pueda mandar
     */
    public ORU_R01 construir(
            DiagnosticReport informe,
            List<Observation> resultados,
            Patient paciente,
            String emisor,
            String instalacion,
            String destino,
            String instalacionDestino,
            String controlId)
            throws HL7Exception, java.io.IOException {

        List<Observation> firmados = resultados.stream()
                .filter(resultado -> resultado.getStatus() == ObservationStatus.FINAL)
                .toList();
        if (firmados.isEmpty()) {
            throw new InformeNoPublicable(
                    ("El informe %s no cita ningún resultado validado. No se manda un ORU con resultados preliminares: "
                                    + "el HIS los leería como definitivos.")
                            .formatted(informe.getIdElement().getIdPart()));
        }

        ORU_R01 mensaje = new ORU_R01();
        mensaje.initQuickstart("ORU", "R01", "P");

        var msh = mensaje.getMSH();
        msh.getSendingApplication().getNamespaceID().setValue(emisor);
        msh.getSendingFacility().getNamespaceID().setValue(instalacion);
        msh.getReceivingApplication().getNamespaceID().setValue(destino);
        msh.getReceivingFacility().getNamespaceID().setValue(instalacionDestino);
        msh.getDateTimeOfMessage().getTime().setValue(sello(Instant.now()));
        msh.getMessageControlID().setValue(controlId);
        msh.getVersionID().getVersionID().setValue("2.5.1");
        msh.getCountryCode().setValue("ES");
        // `MSH-9-3` con el código de la tabla 0354, que para R01 es `ORU_R01` en las dos fuentes de
        // V2.5.1 (ver `adr-0018`). Se declara: un receptor que lo valide tiene derecho a encontrarlo.
        msh.getMessageType().getMessageStructure().setValue("ORU_R01");
        // El charset se declara Y se usa: el escritor MLLP de HAPI mira este campo del mensaje
        // saliente para elegir con qué juego codifica los bytes.
        msh.getCharacterSet(0).setValue(CHARSET);

        var resultadoDelPaciente = mensaje.getPATIENT_RESULT();
        rellenar(resultadoDelPaciente.getPATIENT().getPID(), paciente);

        ORU_R01_ORDER_OBSERVATION orden = resultadoDelPaciente.getORDER_OBSERVATION();
        orden.getORC().getOrderControl().setValue("RE");
        orden.getOBR().getSetIDOBR().setValue("1");
        orden.getOBR()
                .getFillerOrderNumber()
                .getEntityIdentifier()
                .setValue(informe.getIdElement().getIdPart());
        orden.getOBR().getUniversalServiceIdentifier().getIdentifier().setValue("11502-2");
        orden.getOBR().getUniversalServiceIdentifier().getText().setValue("Informe de laboratorio");
        orden.getOBR().getUniversalServiceIdentifier().getNameOfCodingSystem().setValue("LN");
        if (informe.hasIssued()) {
            orden.getOBR()
                    .getResultsRptStatusChngDateTime()
                    .getTime()
                    .setValue(sello(informe.getIssued().toInstant()));
        }
        // `F` a nivel de OBR: el informe está completo. Aquí sí es correcto, porque este mensaje se
        // dispara precisamente cuando el laboratorio ha emitido el informe.
        orden.getOBR().getResultStatus().setValue("F");

        int posicion = 1;
        for (Observation resultado : firmados) {
            rellenar(orden.getOBSERVATION(posicion - 1).getOBX(), resultado, posicion);
            posicion++;
        }
        return mensaje;
    }

    private static void rellenar(PID pid, Patient paciente) throws HL7Exception {
        pid.getSetIDPID().setValue("1");

        int posicion = 0;
        for (Identifier identificador : paciente.getIdentifier()) {
            var cx = pid.getPatientIdentifierList(posicion);
            cx.getIDNumber().setValue(identificador.getValue());
            cx.getAssigningAuthority().getNamespaceID().setValue(autoridadDe(identificador.getSystem()));
            cx.getIdentifierTypeCode().setValue(tipoDe(identificador.getSystem()));
            posicion++;
        }

        HumanName nombre = paciente.getNameFirstRep();
        // El apellido va ENTERO en `PID-5.1`, tal y como está en `HumanName.family`. Partirlo aquí
        // por el espacio sería el mismo error que no partirlo al entrar, en la otra dirección.
        pid.getPatientName(0).getFamilyName().getSurname().setValue(nombre.getFamily());
        for (int i = 0; i < nombre.getGiven().size(); i++) {
            if (i == 0) {
                pid.getPatientName(0)
                        .getGivenName()
                        .setValue(nombre.getGiven().get(i).getValue());
            } else {
                pid.getPatientName(0)
                        .getSecondAndFurtherGivenNamesOrInitialsThereof()
                        .setValue(nombre.getGiven().get(i).getValue());
            }
        }
        if (paciente.hasBirthDate()) {
            pid.getDateTimeOfBirth()
                    .getTime()
                    .setValue(paciente.getBirthDateElement().asStringValue().replace("-", ""));
        }
        if (paciente.hasGender()) {
            pid.getAdministrativeSex()
                    .setValue(
                            switch (paciente.getGender()) {
                                case MALE -> "M";
                                case FEMALE -> "F";
                                case OTHER -> "O";
                                default -> "U";
                            });
        }
    }

    private void rellenar(OBX obx, Observation resultado, int posicion) throws HL7Exception {
        obx.getSetIDOBX().setValue(String.valueOf(posicion));

        String codigoLocal = resultado.getCode().getCoding().stream()
                .filter(coding -> CatalogoDelLaboratorio.SYSTEM.equals(coding.getSystem()))
                .map(org.hl7.fhir.r5.model.Coding::getCode)
                .findFirst()
                .orElseThrow(() -> new InformeNoPublicable("Un resultado del informe no trae código del catálogo."));
        Optional<PruebaDelCatalogo> prueba = catalogo.buscar(codigoLocal);
        Optional<String> loinc = prueba.flatMap(PruebaDelCatalogo::codigoLoinc);

        obx.getObservationIdentifier().getIdentifier().setValue(loinc.orElse(codigoLocal));
        prueba.ifPresent(datos -> {
            try {
                obx.getObservationIdentifier().getText().setValue(datos.display());
            } catch (org.hl7.fhir.exceptions.FHIRException | ca.uhn.hl7v2.model.DataTypeException noSeDeja) {
                throw new InformeNoPublicable("No se pudo escribir el nombre de la prueba en OBX-3: " + noSeDeja);
            }
        });
        obx.getObservationIdentifier().getNameOfCodingSystem().setValue(loinc.isPresent() ? "LN" : "99HISPALIS");
        // El código local viaja SIEMPRE, en la codificación alternativa de OBX-3. Es lo que permite
        // que el resultado se case de vuelta con la petición que lo pidió aunque el LOINC se pierda.
        obx.getObservationIdentifier().getAlternateIdentifier().setValue(codigoLocal);
        obx.getObservationIdentifier().getNameOfAlternateCodingSystem().setValue("99HISPALIS");

        if (resultado.hasValueQuantity()) {
            obx.getValueType().setValue("NM");
            var valor = obx.getObservationValue(0);
            var numero = new ca.uhn.hl7v2.model.v251.datatype.NM(obx.getMessage());
            numero.setValue(resultado.getValueQuantity().getValue().toPlainString());
            valor.setData(numero);
            obx.getUnits().getIdentifier().setValue(resultado.getValueQuantity().getCode());
            obx.getUnits().getNameOfCodingSystem().setValue("UCUM");
        } else {
            obx.getValueType().setValue("ST");
            var valor = obx.getObservationValue(0);
            var texto = new ca.uhn.hl7v2.model.v251.datatype.ST(obx.getMessage());
            texto.setValue(
                    resultado.hasValueStringType()
                            ? resultado.getValueStringType().getValue()
                            : resultado.getValueCodeableConcept().getText());
            valor.setData(texto);
        }

        // `F` = final. Aquí sí lo es: solo llegan resultados validados, y eso se filtró arriba.
        obx.getObservationResultStatus().setValue("F");
        if (resultado.hasEffectiveDateTimeType()) {
            obx.getDateTimeOfTheObservation()
                    .getTime()
                    .setValue(sello(
                            resultado.getEffectiveDateTimeType().getValue().toInstant()));
        }
    }

    private static String sello(Instant momento) {
        return SELLO_V2.format(momento.atZone(ZONA));
    }

    /** La autoridad asignadora que el HIS espera en {@code PID-3.4}, por {@code system}. */
    private static String autoridadDe(String system) {
        return switch (system) {
            case SistemasDeIdentificador.NHC -> "HISPALIS";
            case SistemasDeIdentificador.DNI_NIE -> "MJU";
            case SistemasDeIdentificador.CIP_AUTONOMICO -> "SAS";
            case SistemasDeIdentificador.CIP_SNS -> "MSSSI";
            case SistemasDeIdentificador.NASS -> "TGSS";
            default -> "";
        };
    }

    /** El tipo de identificador de la tabla 0203, que es lo que discrimina cada uno. */
    private static String tipoDe(String system) {
        return switch (system) {
            case SistemasDeIdentificador.NHC -> "MR";
            case SistemasDeIdentificador.DNI_NIE -> "NI";
            case SistemasDeIdentificador.CIP_AUTONOMICO -> "JHN";
            case SistemasDeIdentificador.CIP_SNS -> "HC";
            case SistemasDeIdentificador.NASS -> "SS";
            default -> "";
        };
    }

    /** El informe no se puede publicar hacia fuera tal y como está. */
    public static class InformeNoPublicable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public InformeNoPublicable(String mensaje) {
            super(mensaje);
        }
    }
}
