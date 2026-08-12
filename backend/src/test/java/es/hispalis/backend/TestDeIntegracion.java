package es.hispalis.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 *
 * <p><strong>Y hay terminología</strong>, aunque sea la mínima: desde el ítem 46 no se puede validar
 * un resultado sin preguntar si es crítico. Ver {@link TerminologiaDeLosTests}, incluido cómo lo
 * sustituye una clase que traiga la suya.
 */
@Import(TerminologiaDeLosTests.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=false",
            "hispalis.seguridad.habilitada=false",
            // Y la ENTREGA de notificaciones, por la misma razón que el bus: un relay saliendo cada
            // dos segundos en doscientos tests no prueba nada y ensucia el log. Lo que NO se apaga es
            // anotar lo notificable — eso pasa siempre, y apagarlo cambiaría lo que se está probando.
            "hispalis.notificaciones.habilitado=false",
            // Y el notificador EDO, por lo mismo: un hilo declarando a Salud Pública en doscientos
            // tests no prueba nada. Lo que NO se apaga es DETECTAR lo declarable — eso pasa siempre,
            // en la transacción de la validación, y apagarlo cambiaría lo que se está probando.
            "hispalis.edo.habilitado=false"
        })
public abstract class TestDeIntegracion {

    private static final EmbeddedPostgres POSTGRES = arrancarPostgres();

    private static final Path EXPORTACIONES = directorioTemporal();

    @DynamicPropertySource
    static void apuntarAPostgresEmbebido(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registro.add("spring.datasource.username", () -> "postgres");
        registro.add("spring.datasource.password", () -> "postgres");
        // Las exportaciones van a un temporal y no al directorio de producción, que en un portátil se
        // traduce a crear `C:\var\lib\…` la primera vez que alguien corre los tests. Quien prueba la
        // exportación de verdad se apunta al suyo — `ExportacionMasivaTest` lo hace.
        registro.add("hispalis.exportacion.directorio", EXPORTACIONES::toString);
    }

    private static Path directorioTemporal() {
        try {
            return Files.createTempDirectory("hispalis-tests-exportaciones");
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo preparar el directorio de exportaciones de los tests", e);
        }
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
