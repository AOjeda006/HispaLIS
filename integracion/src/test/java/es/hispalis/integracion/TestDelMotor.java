package es.hispalis.integracion;

import es.hispalis.integracion.arnes.CertificadoDePrueba;
import es.hispalis.integracion.arnes.ClienteMllpDePrueba;
import es.hispalis.integracion.arnes.HisDePrueba;
import es.hispalis.integracion.arnes.IdentidadDePrueba;
import es.hispalis.integracion.arnes.LaboratorioDePrueba;
import es.hispalis.integracion.arnes.OutboxDelBackend;
import es.hispalis.integracion.arnes.TerminologiaDePrueba;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class TestDelMotor {

    protected static final LaboratorioDePrueba LABORATORIO = LaboratorioDePrueba.arrancado();

    /** El HIS al que el motor devuelve el {@code ORU^R01} cuando se emite un informe. */
    protected static final HisDePrueba HIS = HisDePrueba.arrancado();

    /** El servidor de terminología, cargado con los artefactos que publica la guía. */
    protected static final TerminologiaDePrueba TERMINOLOGIA = TerminologiaDePrueba.arrancada();

    /**
     * El servidor de identidad, que comprueba de verdad la aserción del motor (D5).
     *
     * <p>Va encendido en <strong>todos</strong> los tests del motor y no solo en el suyo: así cada
     * canal que escribe demuestra de paso que sale firmado. El laboratorio de prueba no exige el
     * testigo —eso lo haría el de verdad—, pero lo apunta, y {@code BackendServicesTest} lo mira.
     */
    protected static final IdentidadDePrueba IDENTIDAD = IdentidadDePrueba.arrancada();

    private static final EmbeddedPostgres POSTGRES = arrancarPostgres();
    private static final int PUERTO_MLLP = puertoLibre();

    @Autowired
    private DataSource origenDeDatos;

    @LocalServerPort
    private int puertoDeLaConsola;

    /**
     * El servidor de identidad se baja el JWKS del motor, y para eso tiene que saber dónde escucha.
     *
     * <p>Se le dice aquí y no al construirlo porque el puerto es aleatorio y no existe hasta que el
     * contexto arranca — que es justo la situación del {@code compose}, donde la URL se resuelve por
     * el nombre del servicio y no está en ninguna configuración del motor.
     */
    @BeforeEach
    void decirleAlaIdentidadDondeMirar() {
        IDENTIDAD.elMotorPublicaSuJwksEn("http://127.0.0.1:" + puertoDeLaConsola + "/motor/jwks.json");
    }

    @DynamicPropertySource
    static void apuntarAlArnes(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registro.add("spring.datasource.username", () -> "postgres");
        registro.add("spring.datasource.password", () -> "postgres");
        registro.add("hispalis.laboratorio.url", LABORATORIO::url);

        // La identidad va encendida: el motor firma su aserción con una clave efímera y publica su
        // JWKS, igual que en el `compose`. La clave privada no se configura a propósito — probar la
        // ruta de la clave por variable de entorno es cosa de `BackendServicesTest`, que la genera.
        registro.add("hispalis.identidad.habilitada", () -> true);
        registro.add("hispalis.identidad.emisor", IDENTIDAD::emisor);
        registro.add("hispalis.identidad.cliente", () -> "hispalis-motor");
        registro.add("hispalis.mllp.puerto", () -> PUERTO_MLLP);
        registro.add("hispalis.mllp.tls.habilitado", () -> true);
        registro.add("hispalis.mllp.tls.almacen-de-claves", () -> CertificadoDePrueba.almacenDeClaves()
                .toString());
        registro.add("hispalis.mllp.tls.clave", () -> CertificadoDePrueba.CLAVE);

        // El catálogo se pregunta a un servidor de terminología, igual que en el `compose` (D14). El
        // de aquí está cargado con los artefactos de la guía; si SUSHI no ha corrido, no arranca y el
        // mensaje dice qué ejecutar.
        registro.add("hispalis.terminologia.servidor", TERMINOLOGIA::url);

        registro.add("hispalis.his.servidor", () -> "127.0.0.1");
        registro.add("hispalis.his.puerto", HIS::puerto);
        registro.add("hispalis.his.tls", () -> true);
        registro.add("hispalis.his.verificar-certificado", () -> false);
        // El sondeo automático se apaga: los tests llaman a `unaVuelta()` cuando toca. Un test que
        // duerme esperando al temporizador es un test que falla de vez en cuando en la CI.
        registro.add("hispalis.his.sondeo-ms", () -> "3600000");
    }

    /** El origen de datos de la base de pruebas, para los tests que consultan SQL directamente. */
    protected DataSource origenDeDatos() {
        return origenDeDatos;
    }

    /** Un emisor que habla TLS contra el listener de este test. */
    protected static ClienteMllpDePrueba elHis() {
        return new ClienteMllpDePrueba("127.0.0.1", PUERTO_MLLP, true);
    }

    /** El mismo puerto, pero en claro: sirve para comprobar que el TLS está puesto de verdad. */
    protected static ClienteMllpDePrueba elHisSinCifrar() {
        return new ClienteMllpDePrueba("127.0.0.1", PUERTO_MLLP, false);
    }

    /** Dónde escucha el listener, para el arnés que escribe los bytes de sobre a mano. */
    protected static int puertoDelListener() {
        return PUERTO_MLLP;
    }

    private static int puertoLibre() {
        try (ServerSocket sonda = new ServerSocket(0)) {
            return sonda.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo reservar un puerto para el listener MLLP", e);
        }
    }

    /**
     * Arranca PostgreSQL y crea en él el esquema {@code outbox} <strong>del laboratorio</strong>.
     *
     * <p>El {@code outbox} no lo crea la migración del motor —no es suyo— pero el motor lo
     * <strong>lee</strong>: es el bus hasta que llegue Kafka (ítem 30). Tiene que existir
     * <strong>antes</strong> de que arranque el contexto, no en un {@code @BeforeEach}: el sondeo del
     * notificador se dispara al levantarse la aplicación y se encontraría con una tabla que no está.
     * Ver {@link OutboxDelBackend}.
     */
    private static EmbeddedPostgres arrancarPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> cerrar(postgres)));
            OutboxDelBackend.crear(postgres.getPostgresDatabase());
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
