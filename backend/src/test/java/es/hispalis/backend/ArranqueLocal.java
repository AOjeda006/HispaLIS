package es.hispalis.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.SpringApplication;

/**
 * Arranca el backend en local con su propio PostgreSQL, sin Docker y sin instalar nada.
 *
 * <p>Existe porque en este equipo <strong>no hay Docker</strong> (ver el ítem 6 de
 * {@code docs/PLAN.md}) y sin base de datos el servidor JPA de HAPI ni siquiera levanta el contexto.
 * Con esto la web del profesional se puede ejercitar contra la API de verdad hoy, en vez de esperar
 * al {@code docker compose} del ítem 15.
 *
 * <p>Vive en {@code src/test} porque el PostgreSQL embebido es una dependencia de alcance
 * {@code test}, y ahí es donde debe quedarse: <strong>esto no es un modo de despliegue</strong>. La
 * base de datos se crea vacía en un directorio temporal y desaparece al parar el proceso, así que
 * cada arranque empieza sin pacientes.
 *
 * <pre>{@code
 * ./mvnw spring-boot:run -Parranque-local
 * }</pre>
 *
 * <p>El perfil hace falta: {@code useTestClasspath} se ignora si se pasa como {@code -D} en la línea
 * de órdenes, y entonces el arranque muere con un {@code ClassNotFoundException} de esta misma clase
 * que no dice por qué.
 */
public final class ArranqueLocal {

    private ArranqueLocal() {
        // Punto de entrada.
    }

    public static void main(String[] argumentos) {
        EmbeddedPostgres postgres = arrancarPostgres();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> cerrar(postgres)));

        // Las mismas variables que lee `application.yaml`, para no tener un segundo camino de
        // configuración que se pueda desviar del de producción.
        System.setProperty("HISPALIS_BD_URL", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("HISPALIS_BD_USUARIO", "postgres");
        System.setProperty("HISPALIS_BD_CLAVE", "postgres");

        SpringApplication.run(AplicacionHispaLis.class, argumentos);
    }

    private static EmbeddedPostgres arrancarPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo arrancar el PostgreSQL embebido", e);
        }
    }

    private static void cerrar(EmbeddedPostgres postgres) {
        try {
            postgres.close();
        } catch (IOException e) {
            // El proceso se está apagando: no hay a quién informar y no hay nada que reparar.
        }
    }
}
