package es.hispalis.backend.fhir.auditoria;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.AuditEvent.AuditEventAction;

/**
 * Un acceso a la API, ya reducido a lo que se puede registrar.
 *
 * <p>Existe para que {@link TraductorDeTraza} no reciba una {@code RequestDetails} de HAPI. La
 * diferencia no es de estilo: una {@code RequestDetails} trae dentro los parámetros de la búsqueda, el
 * cuerpo de la petición y las cabeceras, y con ella delante lo fácil es acabar copiando cualquiera de
 * las tres en la traza. Aquí no hay nada de eso — <strong>no se puede filtrar lo que no se pasa</strong>.
 *
 * @param interaccion la interacción REST, en el vocabulario del estándar ({@code read},
 *     {@code search-type}, {@code create}…)
 * @param accion {@code C} | {@code R} | {@code U} | {@code D} | {@code E}, para poder listar «todas
 *     las escrituras de ayer» sin conocer los nombres de las interacciones
 * @param cuando el instante en que se levanta acta
 * @param quien el sujeto del testigo, o vacío si no había
 * @param comoUsuario el recurso FHIR del usuario ({@code Practitioner/…}), si el testigo lo traía
 * @param desdeDonde la dirección de red desde la que se llamó. No es dato de paciente
 * @param recursos lo accedido. <strong>Referencias, nunca recursos</strong>
 * @param paciente de quién eran los datos, si se pudo saber
 * @param desenlace si salió bien, mal o muy mal
 */
public record Acceso(
        String interaccion,
        AuditEventAction accion,
        Instant cuando,
        Optional<String> quien,
        Optional<String> comoUsuario,
        Optional<String> desdeDonde,
        List<Recurso> recursos,
        Optional<String> paciente,
        Desenlace desenlace) {

    /**
     * Un recurso tocado por el acceso, y si el servidor llegó a devolverlo.
     *
     * <p>La distinción no es un matiz: decide cómo se escribe la referencia. Ver
     * {@link TraductorDeTraza}.
     *
     * @param referencia {@code Tipo/id}
     * @param devuelto si salió por la respuesta — y por tanto existe— o solo se pidió
     */
    public record Recurso(String referencia, boolean devuelto) {

        public boolean esDe(String tipo) {
            return referencia.startsWith(tipo + "/");
        }
    }

    /** Cómo acabó el acceso, con los códigos del vocabulario estándar de desenlaces. */
    public enum Desenlace {
        CORRECTO("0", "Success", null),
        FALLO_MENOR("4", "Minor failure", "Petición rechazada por el servidor."),
        FALLO_GRAVE("8", "Serious failure", "La petición no se pudo atender por un fallo del servidor.");

        private final String codigo;
        private final String nombre;
        private final String detalle;

        Desenlace(String codigo, String nombre, String detalle) {
            this.codigo = codigo;
            this.nombre = nombre;
            this.detalle = detalle;
        }

        public String codigo() {
            return codigo;
        }

        public String nombre() {
            return nombre;
        }

        /**
         * Una frase fija, nunca el mensaje del error.
         *
         * <p>El mensaje de una excepción de HAPI puede llevar dentro el parámetro que la provocó — y el
         * parámetro de una búsqueda de pacientes es el número de historia. Lo que se registra es la
         * clase de fallo, que es lo que sirve para investigar.
         */
        public Optional<String> detalle() {
            return Optional.ofNullable(detalle);
        }

        /** Del código HTTP de la respuesta al desenlace. */
        public static Desenlace deHttp(int codigo) {
            if (codigo >= 500) {
                return FALLO_GRAVE;
            }
            return codigo >= 400 ? FALLO_MENOR : CORRECTO;
        }
    }
}
