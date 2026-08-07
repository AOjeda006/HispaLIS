package es.hispalis.backend.infraestructura.bus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Saca al bus lo que el dominio dejó apuntado en el {@code outbox}.
 *
 * <h2>Qué garantiza y qué no</h2>
 *
 * <p><strong>Al menos una vez.</strong> Publicar y marcar la fila son dos operaciones distintas, y
 * entre las dos se puede caer el proceso: entonces el hecho se vuelve a publicar en la vuelta
 * siguiente. No se intenta arreglar —haría falta una transacción distribuida entre PostgreSQL y
 * Kafka, que es frágil y bloquea—: se declara, y el consumidor deduplica por {@code hechoId}. El
 * test que importa no es el del camino feliz, es el que reentrega y comprueba que el estado no se
 * mueve.
 *
 * <p><strong>Orden por paciente.</strong> La clave de partición es el {@code pacienteId}, así que
 * todo lo de una persona cae en la misma partición y se consume en el orden en que ocurrió. Sin eso,
 * una validación podría llegar antes que el resultado que valida. Entre pacientes distintos no hay
 * orden y no hace falta que lo haya.
 *
 * <p><strong>Lo que NO hace:</strong> alimentar el modelo de lectura. La proyección FHIR se escribe
 * síncrona en la transacción del dominio (§9); Kafka es para notificaciones, analítica y el
 * notificador EDO del hito 3. El día que una lectura de la API dependa de que un consumidor haya
 * procesado algo, se ha roto <em>read-your-writes</em>.
 *
 * <h2>Con el bus caído</h2>
 *
 * <p>No pasa nada. El hecho está escrito y ahí sigue; la API FHIR nunca deja de aceptar escrituras
 * por esto. El relay reintenta en cada vuelta y avisa <strong>una sola vez</strong> por caída: un
 * aviso por segundo llenaría el log de ruido y taparía lo que sí importa.
 */
public class RelayDelOutbox {

    private static final Logger LOG = LoggerFactory.getLogger(RelayDelOutbox.class);

    private final BandejaDeSalida bandeja;
    private final EsquemasDelBus esquemas;
    private final KafkaTemplate<String, SpecificRecord> kafka;
    private final int tanda;
    private final Duration esperaDeEnvio;

    /** Para no repetir el mismo aviso en cada vuelta mientras el bus siga caído. */
    private boolean avisadoDeQueElBusNoEsta;

    RelayDelOutbox(
            BandejaDeSalida bandeja,
            EsquemasDelBus esquemas,
            KafkaTemplate<String, SpecificRecord> kafka,
            int tanda,
            Duration esperaDeEnvio) {
        this.bandeja = bandeja;
        this.esquemas = esquemas;
        this.kafka = kafka;
        this.tanda = tanda;
        this.esperaDeEnvio = esperaDeEnvio;
    }

    /**
     * La vuelta periódica. Se traga los fallos del bus <strong>a propósito</strong>: dejarlos subir
     * solo conseguiría que el planificador registrara la excepción y volviera a llamar igual.
     */
    @Scheduled(
            fixedDelayString = "${hispalis.bus.intervalo:PT1S}",
            initialDelayString = "${hispalis.bus.intervalo:PT1S}")
    void drenar() {
        try {
            int publicados = drenarUnaVez();
            if (publicados > 0 && avisadoDeQueElBusNoEsta) {
                LOG.info("El bus responde otra vez: {} hecho(s) pendientes publicados.", publicados);
            }
            avisadoDeQueElBusNoEsta = false;
        } catch (RuntimeException e) {
            if (!avisadoDeQueElBusNoEsta) {
                LOG.warn("El bus no está; los hechos se quedan en el outbox y se reintentan. Causa: {}", e.toString());
                avisadoDeQueElBusNoEsta = true;
            } else {
                LOG.debug("El bus sigue sin responder.", e);
            }
        }
    }

    /**
     * Una tanda: publica lo que pueda y devuelve cuántos hechos salieron.
     *
     * <p>Los envíos se encolan todos y después se esperan en orden. Esperar cada uno antes de
     * encolar el siguiente convertiría la latencia de red en el límite del relay; esperarlos después
     * deja que el productor los agrupe y sigue permitiendo marcar exactamente los que llegaron: en
     * cuanto uno falla se corta y el resto se queda pendiente para la vuelta siguiente.
     *
     * @throws BusNoDisponible si el registro de esquemas o el broker no responden
     */
    int drenarUnaVez() {
        List<HechoPendiente> pendientes = bandeja.pendientes(tanda);
        if (pendientes.isEmpty()) {
            return 0;
        }

        List<EnvioEnCurso> enCurso = new ArrayList<>(pendientes.size());
        for (HechoPendiente hecho : pendientes) {
            Optional<Topico> topico = RutaDelHecho.de(hecho.tipo());
            if (topico.isEmpty()) {
                // No tiene tópico y no va a tenerlo: se cierra la fila para que el relay no la
                // vuelva a mirar en cada vuelta. Ver `RutaDelHecho` para el porqué de cada caso.
                bandeja.marcarDescartado(hecho.id());
                continue;
            }
            enCurso.add(enviar(hecho, topico.get()));
        }

        int publicados = 0;
        for (EnvioEnCurso envio : enCurso) {
            confirmar(envio);
            bandeja.marcarPublicado(envio.hecho().id(), envio.topico());
            publicados++;
        }
        return publicados;
    }

    private EnvioEnCurso enviar(HechoPendiente hecho, Topico topico) {
        // Los esquemas se aseguran aquí y no al arrancar: si el registro estaba caído entonces, la
        // aplicación tenía que levantar igual. Es idempotente y solo hace trabajo la primera vez.
        esquemas.asegurarRegistrados();

        SpecificRecord mensaje = TraductorAlBus.de(hecho, topico);
        try {
            // La clave es el paciente: es lo que reparte y lo que ordena.
            return new EnvioEnCurso(
                    hecho,
                    topico,
                    kafka.send(topico.nombre(), hecho.claveDeParticion().toString(), mensaje));
        } catch (RuntimeException e) {
            // Con el broker caído el fallo salta aquí, al pedir los metadatos del tópico, y no al
            // esperar el acuse: `max.block.ms` es lo que lo acota.
            throw new BusNoDisponible("No se pudo encolar el hecho " + hecho.id() + " en " + topico.nombre(), e);
        }
    }

    private void confirmar(EnvioEnCurso envio) {
        try {
            envio.acuse().get(esperaDeEnvio.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusNoDisponible(
                    "Interrumpido esperando el acuse del hecho " + envio.hecho().id(), e);
        } catch (Exception e) {
            throw new BusNoDisponible(
                    "El bus no acusó el hecho " + envio.hecho().id(), e);
        }
    }

    /** Un hecho ya encolado, con el acuse todavía por llegar. */
    private record EnvioEnCurso(
            HechoPendiente hecho, Topico topico, CompletableFuture<SendResult<String, SpecificRecord>> acuse) {}
}
