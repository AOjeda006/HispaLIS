package es.hispalis.backend;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Esperar a que el sistema <strong>haga algo</strong>, no a que pase un rato.
 *
 * <h2>Por qué existe</h2>
 *
 * <p>Cinco clases de test comprueban efectos que ocurren en otro hilo: el notificador EDO abre la
 * declaración y mete al paciente en la cohorte, el relay entrega la notificación y apaga la
 * suscripción agotada, la exportación masiva trabaja en su propio hilo y el barrendero retira lo
 * caducado, y la traza de acceso se escribe <strong>después</strong> de contestar. La
 * forma barata de probar eso es mirar cada 50 ms hasta que se cumpla o hasta agotar un plazo, y esa
 * forma tiene un defecto que no se ve hasta que la máquina va cargada: <strong>el plazo es una
 * apuesta sobre el hardware</strong>. Un test así no falla por lo que prueba, sino porque ese día
 * había un IG Publisher compilando al lado — y `principios/testing.md` es explícito: el
 * no-determinismo <em>se controla inyectándolo, no se acepta</em>; una prueba intermitente es un
 * bug, no deuda tolerable.
 *
 * <h2>Qué hace en su lugar</h2>
 *
 * <p>Se engancha al <strong>mismo punto de extensión</strong> por el que pasan las escrituras de
 * producción ({@link Pointcut#STORAGE_PRECOMMIT_RESOURCE_CREATED} y sus hermanos) y despierta al test
 * cuando el sistema escribe algo del tipo que le interesa. No hay intervalo, no hay muestreo y no hay
 * {@code Thread.sleep}: el test duerme hasta que <em>ocurre</em> lo que espera.
 *
 * <p><strong>El aviso se da al confirmar, no al escribir.</strong> Los puntos {@code PRECOMMIT} se
 * disparan dentro de la transacción, así que despertar ahí dejaría al test mirando una base de datos
 * que todavía no ve el cambio — y volvería a dormirse esperando un segundo aviso que ya no va a
 * llegar. Por eso lo que se registra en el punto de enganche es una
 * {@link TransactionSynchronization}, y el aviso sale en {@code afterCompletion}: cuando el test
 * despierta, el dato <strong>está</strong>.
 *
 * <p><strong>El tipo importa, y no es una optimización.</strong> Cada vez que un test mira, mira por
 * la API — y toda llamada a la API escribe su {@code AuditEvent}. Un aviso sin filtrar convertiría la
 * espera en un bucle que se despierta a sí mismo y machaca al servidor justo mientras se le pide que
 * termine algo. Filtrando por el tipo que puede volver cierta la condición, la espera duerme de
 * verdad.
 *
 * <h2>Lo que este mecanismo no cubre</h2>
 *
 * <p>Solo sirve para condiciones que se vuelven ciertas al escribir un recurso FHIR. Lo que termina
 * en otro sitio —la exportación masiva escribe NDJSON en disco y cierra su trabajo en el esquema del
 * dominio— no pasa por aquí, y su aviso lo pone quien lo sepa con
 * {@link #aQueAvisen(BlockingQueue, Supplier, Predicate, String)}.
 *
 * <p><strong>El plazo que queda no es una espera: es un plazo de seguridad.</strong> Nunca se agota
 * en verde, y agotarlo significa que el hilo de fondo está colgado o muerto — que es exactamente lo
 * que un test debe contar.
 *
 * <h2>⚠️ El aviso es del JVM entero, y tiene que serlo</h2>
 *
 * <p>Costó una tanda de rojos averiguar por qué: <strong>Spring cachea los contextos</strong>, así
 * que cuando {@code ExportacionMasivaTest} corre, el contexto de {@code NotificadorEdoTest} sigue
 * vivo — con su notificador EDO dando vueltas sobre <strong>la misma base de datos y el mismo
 * desplazamiento del outbox</strong>. Los dos consumen el mismo hecho y gana el que llegue antes. Si
 * gana el otro, la cohorte se llena igual y el test pasa… pero la escritura ocurre en
 * <strong>otro</strong> registro de interceptores, y una espera enganchada solo al contexto de su
 * test se queda dormida mirando cómo otro hace el trabajo.
 *
 * <p>Por eso el enganche es <strong>por contexto y para siempre</strong>, y la cola es
 * <strong>una sola del JVM</strong>: si algo escribe en cualquier sitio, esta espera se entera. Los
 * avisos de más son ruido inofensivo —se vuelve a mirar y ya está—; el aviso de menos es un rojo de
 * sesenta segundos. Vale porque Surefire ejecuta esta suite en <strong>un fork y en serie</strong>:
 * solo hay un hilo de test esperando a la vez.
 */
@Interceptor
public final class EsperaDelSistema {

    /**
     * Cuándo se declara colgado el sistema.
     *
     * <p>Generoso a propósito: como en el camino bueno no se espera —se despierta con el aviso—, un
     * plazo largo no cuesta un segundo de suite y sí evita que una máquina cargada convierta un verde
     * en un rojo. Lo que mide no es «cuánto tarda esto», sino «esto no va a pasar nunca».
     */
    private static final Duration PLAZO_DE_SEGURIDAD = Duration.ofSeconds(60);

    private static final EsperaDelSistema LA_DE_ESTE_JVM = new EsperaDelSistema();

    /** Los registros de interceptores ya enganchados: uno por contexto de Spring que haya arrancado. */
    private static final Set<IInterceptorService> ENGANCHADOS = ConcurrentHashMap.newKeySet();

    /** Los tipos de lo que el sistema ha confirmado, en orden y venga del contexto que venga. */
    private static final BlockingQueue<String> CONFIRMADO = new LinkedBlockingQueue<>();

    private EsperaDelSistema() {}

    /**
     * Engancha el registro de interceptores de un contexto, si no lo estaba ya.
     *
     * <p>Idempotente y <strong>sin desenganchar</strong>: un contexto cacheado sigue trabajando
     * después de que termine el test que lo creó, y sus escrituras tienen que seguir avisando.
     */
    public static EsperaDelSistema enganchadaA(IInterceptorService interceptores) {
        if (ENGANCHADOS.add(interceptores)) {
            interceptores.registerInterceptor(LA_DE_ESTE_JVM);
        }
        return LA_DE_ESTE_JVM;
    }

    // ─── El enganche ────────────────────────────────────────────────────────

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void alCrear(IBaseResource creado) {
        avisarAlConfirmar(creado);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void alActualizar(IBaseResource anterior, IBaseResource actual) {
        avisarAlConfirmar(actual);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
    public void alBorrar(IBaseResource borrado) {
        avisarAlConfirmar(borrado);
    }

    private void avisarAlConfirmar(IBaseResource recurso) {
        if (recurso == null) {
            return;
        }
        String tipo = recurso.fhirType();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Sin transacción envolvente no hay confirmación que esperar: lo escrito ya está.
            CONFIRMADO.add(tipo);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int estado) {
                CONFIRMADO.add(tipo);
            }
        });
    }

    // ─── La espera ──────────────────────────────────────────────────────────

    /**
     * Espera a que se cumpla una condición, despertando cada vez que el sistema confirma un recurso
     * del tipo indicado.
     *
     * @param tipo el tipo de recurso cuya escritura puede volver cierta la condición —{@code "Group"},
     *     {@code "Task"}, {@code "AuditEvent"}…—. Es lo que hace que la espera duerma en vez de sondear
     * @param mirar cómo se observa el sistema; se llama una vez antes de dormirse y una por aviso
     * @param yaEsta la condición
     * @param loQueSeEsperaba en una frase, para que el fallo diga qué no llegó a pasar
     * @return lo último observado, ya cumpliendo la condición
     */
    public <T> T aQue(String tipo, Supplier<T> mirar, Predicate<T> yaEsta, String loQueSeEsperaba) {
        // Lo confirmado antes de empezar ya lo ve el primer vistazo; arrastrarlo solo daría vueltas
        // en falso. Se vacía ANTES de mirar: al revés, una escritura caída en medio se perdería.
        CONFIRMADO.clear();
        return esperar(limite -> llegaConfirmacionDe(tipo, limite), mirar, yaEsta, loQueSeEsperaba, tipo);
    }

    /**
     * La misma espera para lo que <strong>no</strong> es una escritura FHIR, con el aviso que ponga
     * quien sepa que ha pasado.
     *
     * <p>Es lo que necesita la exportación masiva: su trabajo termina en un hilo propio y deja un
     * NDJSON en el disco, así que no hay recurso confirmado que sirva de aviso. Lo pone el propio
     * ejecutor, envuelto en el test.
     *
     * @param avisos la cola donde el sistema deposita algo —lo que sea— al terminar
     */
    public static <T> T aQueAvisen(
            BlockingQueue<?> avisos, Supplier<T> mirar, Predicate<T> yaEsta, String loQueSeEsperaba) {
        avisos.clear();
        return esperar(limite -> llegaAlgoA(avisos, limite), mirar, yaEsta, loQueSeEsperaba, "aviso del sistema");
    }

    /** Cómo se duerme hasta el siguiente aviso. Devuelve {@code false} si se agotó el plazo. */
    @FunctionalInterface
    private interface HastaElSiguienteAviso {
        boolean dormir(Instant limite);
    }

    private static <T> T esperar(
            HastaElSiguienteAviso dormir,
            Supplier<T> mirar,
            Predicate<T> yaEsta,
            String loQueSeEsperaba,
            String queAviso) {
        Instant limite = Instant.now().plus(PLAZO_DE_SEGURIDAD);
        T ultimo = mirar.get();
        while (!yaEsta.test(ultimo)) {
            if (!dormir.dormir(limite)) {
                throw new AssertionError(
                        ("Se agotó el plazo de seguridad de %s esperando %s: el sistema no volvió a dar ni un %s. "
                                        + "Eso no es lentitud de la máquina, es que ya no va a pasar.")
                                .formatted(PLAZO_DE_SEGURIDAD, loQueSeEsperaba, queAviso));
            }
            ultimo = mirar.get();
        }
        return ultimo;
    }

    /** Duerme hasta que se confirme un recurso <strong>de ese tipo</strong>; lo demás se descarta. */
    private boolean llegaConfirmacionDe(String tipo, Instant limite) {
        String recibido;
        while ((recibido = siguiente(CONFIRMADO, limite)) != null) {
            if (tipo.equals(recibido)) {
                return true;
            }
        }
        return false;
    }

    private static boolean llegaAlgoA(BlockingQueue<?> avisos, Instant limite) {
        return siguiente(avisos, limite) != null;
    }

    private static <T> T siguiente(BlockingQueue<T> cola, Instant limite) {
        long queda = Duration.between(Instant.now(), limite).toMillis();
        if (queda <= 0) {
            return null;
        }
        try {
            return cola.poll(queda, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Se interrumpió la espera antes de que el sistema avisara.", interrumpido);
        }
    }
}
