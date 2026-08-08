package es.hispalis.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de los tests que necesitan la aplicación entera en marcha, con su base de datos.
 *
 * <p>La proyección FHIR de HAPI no arranca sin una base de datos real, así que aquí se levanta
 * <strong>PostgreSQL de verdad</strong> —el mismo motor y el mismo dialecto que en producción—
 * desde un binario que se ejecuta en proceso. No hace falta Docker: la decisión y sus alternativas
 * están en la sección del ítem 6 de {@code docs/PLAN.md}.
 *
 * <p>La instancia es <strong>una sola para toda la ejecución</strong>. Arrancar PostgreSQL cuesta
 * unos segundos y hacerlo por clase de test multiplicaría ese coste sin ganar aislamiento real: el
 * aislamiento entre tests lo da la transacción, no el proceso.
 *
 * <p><strong>El bus va apagado.</strong> Estos tests no van del bus, y con el relay encendido cada
 * uno arrastraría un cliente de Kafka reintentando contra un broker que no existe. Se apaga aquí y
 * no en la configuración de la aplicación a propósito: el valor por defecto de producción tiene que
 * seguir siendo «encendido», y quien quiera el bus —{@code RelayDelOutboxTest}— lo enciende él.
 *
 * <p><strong>La seguridad también va apagada</strong>, y por la misma razón: estos tests prueban el
 * comportamiento clínico de la API, no quién puede llamarla, y exigirles un testigo obligaría a
 * levantar un servidor de identidad para comprobar que un espécimen rechazado no produce resultado.
 * Quien prueba la seguridad —{@code SeguridadSmartTest}— la enciende él y se emite sus propios
 * testigos. El valor por defecto de producción sigue siendo «encendida».
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hispalis.bus.habilitado=false", "hispalis.seguridad.habilitada=false"})
public abstract class TestDeIntegracion {

    private static final EmbeddedPostgres POSTGRES = arrancarPostgres();

    @DynamicPropertySource
    static void apuntarAPostgresEmbebido(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registro.add("spring.datasource.username", () -> "postgres");
        registro.add("spring.datasource.password", () -> "postgres");
    }

    private static EmbeddedPostgres arrancarPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> cerrar(postgres)));
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo arrancar el PostgreSQL embebido de los tests", e);
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
