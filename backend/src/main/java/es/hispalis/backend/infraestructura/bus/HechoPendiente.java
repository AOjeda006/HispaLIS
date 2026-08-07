package es.hispalis.backend.infraestructura.bus;

import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Una fila del {@code outbox} esperando a salir.
 *
 * <p>No es el agregado {@link es.hispalis.backend.dominio.hecho.Hecho}: aquel es lo que el dominio
 * construye y valida al apuntar; este es lo que el relay lee después, quizá en otro proceso y desde
 * luego en otra transacción. Compartir la clase acoplaría el bus a las reglas de construcción del
 * dominio y, peor, sugeriría que el relay puede fabricar hechos. No puede: solo los reparte.
 *
 * @param id identidad del hecho, y clave de idempotencia para el consumidor
 * @param tipo qué pasó
 * @param claveDeParticion el paciente, que es por donde se reparte
 * @param carga referencias por nombre; nunca PHI, y eso lo garantiza el agregado al apuntarlo
 * @param creadoEn cuándo se apuntó
 */
record HechoPendiente(UUID id, TipoDeHecho tipo, UUID claveDeParticion, Map<String, String> carga, Instant creadoEn) {

    /**
     * Una referencia que el esquema declara obligatoria.
     *
     * @throws IllegalStateException si no está. Es un fallo de programación —el caso de uso que
     *     apuntó el hecho no puso lo que su tópico exige—, no una fila corrupta: dejarlo pasar
     *     publicaría un mensaje que el esquema no admite y el fallo aparecería más lejos.
     */
    String referenciaObligatoria(String nombre) {
        String valor = carga.get(nombre);
        if (valor == null) {
            throw new IllegalStateException(
                    "El hecho %s (%s) no lleva «%s», que su tópico declara obligatorio.".formatted(id, tipo, nombre));
        }
        return valor;
    }

    /** Una referencia que el esquema declara opcional. {@code null} si no viene. */
    String referenciaOpcional(String nombre) {
        return carga.get(nombre);
    }
}
