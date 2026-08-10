package es.hispalis.backend.dominio.edo;

import es.hispalis.backend.dominio.edo.NotificacionEdo.Acuse;

/**
 * A quién se declara. El puerto de salida del laboratorio hacia el sistema de vigilancia.
 *
 * <p>El destinatario real —Redalerta, del SVEA— se modela de forma <strong>verosímil, no fiel</strong>
 * (§15 del diseño): su contrato no es público, y una integración inventada con una administración de
 * verdad da falso realismo y no se puede validar. Lo que sí es real es la obligación, que alcanza
 * también a los laboratorios privados.
 *
 * <p>Que sea un puerto tiene una consecuencia concreta y no es ceremonia: el día que el contrato de
 * verdad exista, se escribe otro adaptador y <strong>ni el agregado ni el notificador cambian</strong>.
 * Lo que se está probando hoy —que sin acuse no hay declaración y que un destinatario caído no
 * bloquea al laboratorio— seguirá valiendo entonces.
 */
public interface SaludPublica {

    /**
     * Manda la declaración y espera el acuse.
     *
     * @param declaracion qué se declara. <strong>Sin filiación:</strong> el agregado no la tiene.
     * @return la respuesta del destinatario, que puede ser un acuse, un «recibido sin registrar» o un
     *     rechazo. Los tres son respuestas y se distinguen; lo que no llega a ninguna parte se cuenta
     *     como {@link NoLlego}.
     */
    Respuesta declarar(NotificacionEdo declaracion);

    /** Qué contestó Salud Pública. Cuatro casos, porque los cuatro pasan y hay que distinguirlos. */
    sealed interface Respuesta {

        /** La tiene y la ha registrado. Es el único que cierra la obligación. */
        record Acusada(Acuse acuse) implements Respuesta {}

        /**
         * Le ha llegado y no ha devuelto número de registro.
         *
         * <p>Es el caso que más fácil se cuela por bueno, porque a nivel de transporte todo ha ido
         * bien: sin este caso aparte, un {@code 200} vacío pasaría por declaración hecha.
         */
        record RecibidaSinRegistro(String detalle) implements Respuesta {}

        /** Ha contestado que no la admite, con su motivo. Es una respuesta, no una avería. */
        record Rechazada(String motivo) implements Respuesta {}

        /** No hubo forma de entregarla: no está, no contesta o contesta un error del canal. */
        record NoLlego(String motivo) implements Respuesta {}
    }
}
