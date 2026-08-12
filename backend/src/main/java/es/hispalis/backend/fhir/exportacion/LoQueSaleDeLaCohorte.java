package es.hispalis.backend.fhir.exportacion;

import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.Address;
import org.hl7.fhir.r5.model.DateType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.Specimen;
import org.springframework.stereotype.Component;

/**
 * Qué se lleva un fichero de exportación, y qué se queda dentro.
 *
 * <h2>Una divergencia consciente del estándar</h2>
 *
 * <p>Un servidor Bulk Data conforme entrega <strong>el compartimento del paciente tal cual</strong>,
 * filiación incluida, y protege el dato por acceso: <em>scopes</em>, caducidad y borrado. Este no. Lo
 * que sale es una cohorte seudonimizada, y queda escrito aquí, en el perfil {@code CohorteVigilancia}
 * y en la {@code OperationDefinition} que publica la guía — porque un cliente que espere lo primero y
 * reciba lo segundo concluiría que el laboratorio no tiene nombres, en vez de que no los cede.
 *
 * <p>La razón es el invariante 6 llevado a su caso extremo. Con una declaración EDO se cede un caso;
 * con un {@code $export} se ceden todos a la vez, en un fichero que después vive en un disco. El
 * riesgo no es proporcional al número de registros: es peor, porque un volcado de población permite
 * cruces que un caso suelto no permite.
 *
 * <h2>Qué se conserva, y por qué eso sí</h2>
 *
 * <p>Lo epidemiológico: <strong>sexo, año de nacimiento y municipio</strong>. Los tres son
 * exactamente lo que una unidad de vigilancia necesita para describir un brote —a quién afecta y
 * dónde— y ninguno identifica por sí solo. La fecha de nacimiento completa sí acerca bastante a la
 * identificación cuando se cruza con el municipio, así que se recorta al año.
 *
 * <h2>Y el texto libre, fuera</h2>
 *
 * <p>{@code Observation.note} y {@code Specimen.note} se quitan aunque no sean filiación. Un campo de
 * texto escrito por una persona con prisa contiene, antes o después, el nombre de otra: «avisado el
 * Dr. X», «la madre pregunta por…». No hay forma de comprobarlo, y por eso no se manda.
 */
@Component
public class LoQueSaleDeLaCohorte {

    /**
     * Los tipos que este servidor exporta.
     *
     * <p>Tres, y los tres se justifican: el paciente seudonimizado sitúa el caso, el resultado es el
     * dato y la muestra dice cuándo y de qué se obtuvo. {@code DiagnosticReport} y
     * {@code ServiceRequest} <strong>quedan fuera a propósito</strong>: el primero lleva una conclusión
     * redactada y el segundo la indicación clínica que escribió el peticionario. Los dos son texto
     * libre de un profesional, y ninguno aporta nada que no esté ya en el resultado.
     */
    public static final List<String> TIPOS_EXPORTABLES = List.of("Patient", "Observation", "Specimen");

    /**
     * Devuelve el recurso tal y como debe salir, o vacío si de ese tipo no sale nada.
     *
     * <p>El {@code switch} es exhaustivo por lista blanca y no por lista negra: un tipo nuevo no se
     * exporta hasta que alguien decida que sí, que es el sentido correcto del fallo.
     */
    public Optional<Resource> comoSale(Resource recurso) {
        return switch (recurso) {
            case Patient paciente -> Optional.of(seudonimizar(paciente));
            case Observation resultado -> Optional.of(sinNotas(resultado));
            case Specimen muestra -> Optional.of(sinNotas(muestra));
            default -> Optional.empty();
        };
    }

    /**
     * El paciente, reducido a lo que describe un caso.
     *
     * <p>Se construye uno nuevo en vez de vaciar el original, y no es un detalle: quitar elementos de
     * un recurso deja al siguiente que añada uno —una extensión, un {@code contact}— colándose sin que
     * nadie se entere. Con una lista de lo que <strong>sí</strong> se copia, lo nuevo no sale por
     * defecto.
     */
    private static Patient seudonimizar(Patient original) {
        Patient anonimo = new Patient();
        anonimo.setId(original.getIdElement().toUnqualifiedVersionless());
        // Sin `meta.profile`: un paciente sin NHC no cumple `PacienteLabES`, que lo exige `1..1`.
        // Declarar un perfil que no se cumple es peor que no declarar ninguno.
        anonimo.setActive(original.getActive());
        anonimo.setGender(original.getGender());

        if (original.hasBirthDate()) {
            // Solo el año. La fecha completa, cruzada con el municipio, identifica más de lo que
            // parece — y para describir un brote basta con la edad aproximada.
            anonimo.setBirthDateElement(
                    new DateType(String.valueOf(original.getBirthDateElement().getYear())));
        }

        for (Address direccion : original.getAddress()) {
            Address donde = anonimo.addAddress();
            donde.setCity(direccion.getCity());
            donde.setState(direccion.getState());
            donde.setCountry(direccion.getCountry());
            // El código INE del municipio, que es la extensión propia del proyecto (D9). Es el dato con
            // el que se dibuja un mapa de casos, y va sobre el municipio, no sobre la calle.
            direccion.getCityElement().getExtension().stream()
                    .map(org.hl7.fhir.r5.model.Extension::copy)
                    .forEach(donde.getCityElement()::addExtension);
        }

        // Vivo o no, SIN fecha: en una legionelosis el desenlace es información epidemiológica de
        // primer orden, y la fecha exacta de defunción es un identificador de manual. Se mira el valor
        // y no `hasDeceased()`: un `deceasedBoolean = false` también es un elemento presente, y
        // copiarlo como «sí» convertiría a los vivos en muertos.
        if (haFallecido(original)) {
            anonimo.setDeceased(new org.hl7.fhir.r5.model.BooleanType(true));
        }
        return anonimo;
    }

    private static boolean haFallecido(Patient paciente) {
        return paciente.hasDeceasedDateTimeType()
                || (paciente.hasDeceasedBooleanType()
                        && paciente.getDeceasedBooleanType().booleanValue());
    }

    private static Observation sinNotas(Observation original) {
        Observation copia = original.copy();
        copia.setNote(null);
        return copia;
    }

    private static Specimen sinNotas(Specimen original) {
        Specimen copia = original.copy();
        copia.setNote(null);
        return copia;
    }
}
