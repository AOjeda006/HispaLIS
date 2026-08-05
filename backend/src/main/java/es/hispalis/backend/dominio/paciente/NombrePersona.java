package es.hispalis.backend.dominio.paciente;

import es.hispalis.backend.dominio.DatoInvalido;

/**
 * El nombre de una persona, con el apellido español modelado como es.
 *
 * <p><strong>{@code apellidos} lleva el nombre familiar completo y sin trocear</strong>
 * —«Muñoz de la Torre», «de la Peña Álvarez»—. Partirlo por el espacio para separar el paterno del
 * materno es el error clásico y falla con cualquier apellido compuesto, con partícula o extranjero.
 *
 * <p>Cuando se conoce la descomposición se guarda <em>aparte</em>, en {@code apellidoPadre} y
 * {@code apellidoMadre}, que es lo que hacen las extensiones estándar de FHIR sobre
 * {@code HumanName.family}. Nunca se deduce: o viene dada, o no consta.
 *
 * @param apellidos nombre familiar completo, obligatorio
 * @param nombreDePila nombres propios, tal y como los da el paciente
 * @param apellidoPadre primer apellido, si se conoce la descomposición; {@code null} si no
 * @param apellidoMadre segundo apellido, si se conoce la descomposición; {@code null} si no
 */
public record NombrePersona(String apellidos, String nombreDePila, String apellidoPadre, String apellidoMadre) {

    public NombrePersona {
        if (apellidos == null || apellidos.isBlank()) {
            throw new DatoInvalido("El paciente necesita al menos un apellido: es como se le identifica.");
        }
        apellidos = apellidos.strip();
        nombreDePila = nombreDePila == null ? "" : nombreDePila.strip();
        apellidoPadre = normalizar(apellidoPadre);
        apellidoMadre = normalizar(apellidoMadre);
    }

    private static String normalizar(String parte) {
        if (parte == null || parte.isBlank()) {
            return null;
        }
        return parte.strip();
    }
}
