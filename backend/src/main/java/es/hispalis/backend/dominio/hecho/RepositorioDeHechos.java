package es.hispalis.backend.dominio.hecho;

/**
 * Puerto de salida del {@code outbox}.
 *
 * <p>Solo apunta. Publicar es de otro —el relay a Kafka del hito 2—, y esa separación es la razón de
 * ser del patrón: escribir el hecho en la misma transacción que el dominio es lo que garantiza que no
 * se pierda si el bus está caído, y lo que impide anunciar algo que la transacción acabó revirtiendo.
 */
public interface RepositorioDeHechos {

    /** Deja el hecho apuntado, sin publicar. */
    void registrar(Hecho hecho);
}
