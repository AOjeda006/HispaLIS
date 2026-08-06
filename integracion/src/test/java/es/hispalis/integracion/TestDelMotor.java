package es.hispalis.integracion;

import es.hispalis.integracion.arnes.CertificadoDePrueba;
import es.hispalis.integracion.arnes.ClienteMllpDePrueba;
import es.hispalis.integracion.arnes.LaboratorioDePrueba;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de los tests que levantan el motor entero: listener MLLP, almacén y cliente FHIR.
 *
 * <p>Las tres piezas de alrededor son <strong>de verdad</strong>, no simulacros:
 *
 * <ul>
 *   <li><strong>PostgreSQL</strong> en proceso, el mismo motor que en producción. La deduplicación la
 *       hace una restricción única, y una restricción única solo se puede probar contra el motor que
 *       la va a aplicar.
 *   <li><strong>El listener con TLS puesto</strong>, con un certificado generado en {@code target/}.
 *       Probar el canal en claro y confiar en que con TLS irá igual es probar otra cosa.
 *   <li><strong>Un servidor HTTP</strong> que recibe lo que el motor envía. Ver
 *       {@link LaboratorioDePrueba}.
 * </ul>
 */
@SpringBootTest
public abstract class TestDelMotor {

    protected static final LaboratorioDePrueba LABORATORIO = LaboratorioDePrueba.arrancado();

    private static final EmbeddedPostgres POSTGRES = arrancarPostgres();
    private static final int PUERTO_MLLP = puertoLibre();

    @DynamicPropertySource
    static void apuntarAlArnes(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registro.add("spring.datasource.username", () -> "postgres");
        registro.add("spring.datasource.password", () -> "postgres");
        registro.add("hispalis.laboratorio.url", LABORATORIO::url);
        registro.add("hispalis.mllp.puerto", () -> PUERTO_MLLP);
        registro.add("hispalis.mllp.tls.habilitado", () -> true);
        registro.add("hispalis.mllp.tls.almacen-de-claves", () -> CertificadoDePrueba.almacenDeClaves()
                .toString());
        registro.add("hispalis.mllp.tls.clave", () -> CertificadoDePrueba.CLAVE);
    }

    /** Un emisor que habla TLS contra el listener de este test. */
    protected static ClienteMllpDePrueba elHis() {
        return new ClienteMllpDePrueba("127.0.0.1", PUERTO_MLLP, true);
    }

    /** El mismo puerto, pero en claro: sirve para comprobar que el TLS está puesto de verdad. */
    protected static ClienteMllpDePrueba elHisSinCifrar() {
        return new ClienteMllpDePrueba("127.0.0.1", PUERTO_MLLP, false);
    }

    private static int puertoLibre() {
        try (ServerSocket sonda = new ServerSocket(0)) {
            return sonda.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo reservar un puerto para el listener MLLP", e);
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
