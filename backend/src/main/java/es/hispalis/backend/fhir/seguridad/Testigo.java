package es.hispalis.backend.fhir.seguridad;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * El testigo de acceso ya interpretado: qué se concedió y sobre quién.
 *
 * <p>Es la única forma en la que el resto del borde ve la autorización. Nadie más vuelve a mirar el
 * JWT: quien lo valida y lo abre es el filtro de Spring Security, y de ahí sale esto.
 *
 * <p><strong>El contexto de lanzamiento se lee del testigo, no de lo que diga la aplicación.</strong>
 * SMART entrega el {@code patient} en la respuesta del testigo para que la aplicación sepa sobre quién
 * abrirse — eso es ergonomía de flujo de trabajo. Aquí se usa para decidir qué datos salen, y para eso
 * solo vale lo que viene firmado: una aplicación puede equivocarse de paciente, y un cliente hostil
 * puede pedir el que quiera.
 *
 * @param ambitos los permisos concedidos, ya interpretados; los <em>scopes</em> que no se entienden no
 *     están aquí
 * @param pacienteEnContexto el id lógico del {@code Patient} sobre el que se lanzó, si lo hay
 * @param fhirUser el recurso FHIR que representa al usuario ({@code Practitioner/…}), si lo hay
 * @param sujeto el {@code sub} del testigo — identifica al cliente o al usuario para la auditoría, y
 *     nunca es un dato clínico
 */
public record Testigo(
        List<AmbitoSmart> ambitos, Optional<String> pacienteEnContexto, Optional<String> fhirUser, String sujeto) {

    /**
     * Construye el testigo a partir de los <em>claims</em> tal y como llegan.
     *
     * @param scope el {@code scope} del testigo, separado por espacios, o {@code null}
     * @param paciente el {@code patient} del contexto de lanzamiento, o {@code null}
     * @param fhirUser el {@code fhirUser}, o {@code null}
     * @param sujeto el {@code sub}
     */
    public static Testigo de(String scope, String paciente, String fhirUser, String sujeto) {
        List<AmbitoSmart> ambitos = scope == null || scope.isBlank()
                ? List.of()
                : Arrays.stream(scope.split("\\s+"))
                        .map(AmbitoSmart::de)
                        .flatMap(Optional::stream)
                        .toList();
        return new Testigo(
                ambitos,
                Optional.ofNullable(paciente).filter(valor -> !valor.isBlank()),
                Optional.ofNullable(fhirUser).filter(valor -> !valor.isBlank()),
                sujeto);
    }

    /** ¿Hay algún ámbito de este contexto? */
    public boolean actuaComo(AmbitoSmart.Contexto contexto) {
        return ambitos.stream().anyMatch(ambito -> ambito.contexto() == contexto);
    }

    /** Los ámbitos de un contexto concreto. */
    public List<AmbitoSmart> ambitosDe(AmbitoSmart.Contexto contexto) {
        return ambitos.stream().filter(ambito -> ambito.contexto() == contexto).toList();
    }

    /**
     * ¿Está el acceso limitado a un paciente concreto?
     *
     * <p>Un testigo con ámbito {@code patient/} <strong>y sin</strong> paciente en contexto no es un
     * permiso amplio: es un testigo mal emitido, y lo que corresponde es no dejarle ver nada. Quien
     * decide eso es {@code ConsentimientoDelPaciente}, que es donde vive la regla.
     */
    public boolean limitadoAUnPaciente() {
        return actuaComo(AmbitoSmart.Contexto.PACIENTE);
    }
}
