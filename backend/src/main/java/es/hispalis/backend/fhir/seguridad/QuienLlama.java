package es.hispalis.backend.fhir.seguridad;

import java.util.Optional;

/**
 * De dónde sale el testigo de la petición que se está atendiendo.
 *
 * <p>Es un puerto de una sola línea y existe por dos razones. La primera es que los interceptores del
 * borde no tengan que saber que detrás hay un {@code SecurityContextHolder} de Spring: lo que
 * necesitan es «quién llama», no cómo se guarda. La segunda es poder probar la autorización y el
 * consentimiento <strong>sin levantar un servidor de identidad</strong>, que es lo que permite que
 * esos tests corran en un segundo y no en un minuto.
 */
@FunctionalInterface
public interface QuienLlama {

    /** El testigo de la petición en curso, o vacío si no hay ninguno (p. ej. {@code metadata}). */
    Optional<Testigo> testigo();
}
