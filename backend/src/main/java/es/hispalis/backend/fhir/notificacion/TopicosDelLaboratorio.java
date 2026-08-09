package es.hispalis.backend.fhir.notificacion;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hl7.fhir.r5.model.SubscriptionTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los tópicos que este laboratorio deja escuchar, publicados en su propio servidor.
 *
 * <p><strong>Por qué el servidor tiene que tenerlos dentro y no basta con que estén en la guía.</strong>
 * En R5 el criterio de una suscripción vive en el {@code SubscriptionTopic}, y {@code GET
 * [base]/SubscriptionTopic} es la forma en que un cliente descubre a qué se puede suscribir. Un
 * servidor que dispara notificaciones por un tópico que no publica obliga a leer su documentación
 * para saber qué escucha, que es exactamente lo que R5 vino a quitar de en medio.
 *
 * <p><strong>Y por qué el fichero está aquí duplicado.</strong> Es literalmente el que produce SUSHI
 * al compilar {@code ig/input/fsh/notificaciones/}, copiado a {@code resources/conformidad/}. La
 * alternativa —escribir el disparador otra vez en Java— sería tener la regla en dos sitios que se
 * pueden contradecir sin que nada avise. Contra la copia sí se puede avisar: {@code ci-ig} compara
 * los dos ficheros y falla si divergen, que es el mismo tipo de puerta que la de «cada perfil tiene
 * ejemplo». La copia existe porque el backend se construye sin la guía delante, no porque haya dos
 * fuentes de verdad.
 */
@Component
public class TopicosDelLaboratorio {

    private static final Logger LOG = LoggerFactory.getLogger(TopicosDelLaboratorio.class);

    /** El tópico del hito 3. La lista es explícita porque cada tópico es una decisión, no un fichero suelto. */
    private static final List<String> DE_LA_GUIA = List.of("conformidad/SubscriptionTopic-resultado-validado.json");

    private final FhirContext contexto;
    private final DaoRegistry daos;

    /** Lo último leído del almacén. {@code null} = todavía no se ha preguntado, o alguien tocó un tópico. */
    private volatile List<SubscriptionTopic> cache;

    public TopicosDelLaboratorio(FhirContext contexto, DaoRegistry daos) {
        this.contexto = contexto;
        this.daos = daos;
    }

    /**
     * Deja los tópicos publicados al arrancar, con {@code PUT} e id fijo.
     *
     * <p>Es idempotente por construcción: el id sale del recurso, así que arrancar cien veces deja
     * un tópico y no cien. Va en {@code ApplicationReadyEvent} y no en un {@code @PostConstruct}
     * porque hace falta que el registro de DAO y las migraciones estén completos.
     *
     * <p>Un fallo aquí <strong>no impide arrancar</strong>: sin tópico no hay notificaciones, que es
     * una función de más; con la API caída no hay laboratorio. Se avisa fuerte y se sigue.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void publicar() {
        for (String fichero : DE_LA_GUIA) {
            try {
                SubscriptionTopic topico = leer(fichero);
                daos.getResourceDao(SubscriptionTopic.class).update(topico, new SystemRequestDetails());
                olvidar();
                LOG.info("Tópico de notificación publicado: {}", topico.getUrl());
            } catch (RuntimeException e) {
                LOG.error(
                        "No se ha podido publicar el tópico de «{}», así que no habrá notificaciones por él. "
                                + "El laboratorio arranca igual. Causa: {}",
                        fichero,
                        e.toString());
            }
        }
    }

    /**
     * Los tópicos que hoy publica este servidor.
     *
     * <p>Se pregunta en <strong>cada escritura de la proyección</strong>, así que se guarda lo leído:
     * sin caché, dar de alta un paciente costaría una búsqueda de más. Y se
     * {@link #olvidar() olvida} en cuanto alguien escribe un {@code SubscriptionTopic}, que es lo que
     * evita el defecto habitual de estos cachés — corregir un tópico y tener que reiniciar para que
     * surta efecto, justo lo contrario de por qué el criterio se sacó del código.
     *
     * <p><strong>No se filtra por {@code status}.</strong> El estado de publicación de un recurso de
     * conformidad habla de la madurez de la <em>definición</em> —esta guía entera está en
     * {@code draft}—, no de si el servidor lo honra. Lo que decide si se entrega o no es el estado de
     * la {@code Subscription}.
     */
    public List<SubscriptionTopic> publicados() {
        List<SubscriptionTopic> sabidos = cache;
        if (sabidos != null) {
            return sabidos;
        }
        List<SubscriptionTopic> leidos = daos
                .getResourceDao(SubscriptionTopic.class)
                .search(new SearchParameterMap().setLoadSynchronous(true), new SystemRequestDetails())
                .getAllResources()
                .stream()
                .map(SubscriptionTopic.class::cast)
                .toList();
        cache = leidos;
        return leidos;
    }

    /** Tira lo cacheado. Lo llama quien ve pasar la escritura de un tópico. */
    public void olvidar() {
        cache = null;
    }

    private SubscriptionTopic leer(String fichero) {
        try (InputStream flujo = new ClassPathResource(fichero).getInputStream()) {
            return (SubscriptionTopic)
                    contexto.newJsonParser().parseResource(new String(flujo.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException noSePuedeLeer) {
            throw new UncheckedIOException(noSePuedeLeer);
        }
    }
}
