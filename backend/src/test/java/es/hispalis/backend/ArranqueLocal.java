package es.hispalis.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.SpringApplication;

/**
 * Arranca el backend en local con su propio PostgreSQL, sin Docker y sin instalar nada.
 *
 * <p>Existe porque en este equipo <strong>no hay Docker</strong> (ver {@code docs/adr/adr-0013}) y
 * sin base de datos el servidor JPA de HAPI ni siquiera levanta el contexto. Con esto la web del
 * profesional se puede ejercitar contra la API de verdad hoy, en vez de esperar al
 * {@code docker compose}.
 *
 * <p>Vive en {@code src/test} porque el PostgreSQL embebido es una dependencia de alcance
 * {@code test}, y ahí es donde debe quedarse: <strong>esto no es un modo de despliegue</strong>. La
 * base de datos se crea vacía en un directorio temporal y desaparece al parar el proceso, así que
 * cada arranque empieza sin pacientes.
 *
 * <p><strong>Y con la seguridad apagada</strong>, porque aquí no hay ningún proveedor de identidad
 * levantado. La API <em>abierta de par en par</em> es exactamente lo que sale de aquí, y el arranque
 * lo avisa en el log; para ejercitar la seguridad de verdad está el {@code docker compose}, que trae
 * su Keycloak.
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

        // Y la seguridad apagada, porque en este arranque no hay Keycloak al que preguntar. La API se
        // niega a levantar con la seguridad puesta y sin emisor —bien hecho: un servidor que dice
        // exigir testigo y no sabe quién los firma es peor que uno abierto—, así que desde que existe
        // el hito 2 este perfil moría al arrancar. Apagarla aquí es la única lectura coherente con lo
        // que este arranque es: la API queda ABIERTA y el propio arranque lo grita en el log.
        System.setProperty("hispalis.seguridad.habilitada", "false");

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
