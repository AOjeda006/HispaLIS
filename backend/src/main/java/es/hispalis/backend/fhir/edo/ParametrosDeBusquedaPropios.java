package es.hispalis.backend.fhir.edo;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hl7.fhir.r5.model.SearchParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los parámetros de búsqueda que este servidor añade a los del núcleo, publicados en él mismo.
 *
 * <h2>Por qué hace falta uno propio</h2>
 *
 * <p>R5 trae dieciocho parámetros estándar para {@code Task} y <strong>ninguno</strong> cae sobre
 * {@code Task.restriction.period}, que es donde el estándar quiere que viva el plazo dentro del cual
 * se busca cumplir la tarea. Sin este parámetro, «qué declaraciones se han pasado de plazo» solo se
 * puede contestar descargándolas todas y mirándolas una a una en el cliente — que es lo que hace que
 * nadie las mire. El más cercano, {@code period}, cubre {@code Task.executionPeriod}: cuándo se hizo el
 * trabajo, no hasta cuándo había de plazo.
 *
 * <p>No es una extensión del modelo: no se añade dato ninguno, se declara cómo se indexa uno que ya
 * está. Es exactamente para lo que una guía de implementación publica {@code SearchParameter}.
 *
 * <h2>Y por qué el fichero está aquí duplicado</h2>
 *
 * <p>Es literalmente el que produce SUSHI, copiado a {@code resources/conformidad/}, por lo mismo que
 * el {@code SubscriptionTopic}: el backend se construye sin la guía delante. Escribir la
 * {@code expression} otra vez en Java sería tener la misma regla en dos sitios que se pueden
 * contradecir sin que nada avise; contra una copia sí se puede avisar, y {@code ci-ig} compara los dos
 * ficheros y falla si divergen.
 *
 * <h2>El registro hay que refrescarlo a mano</h2>
 *
 * <p>HAPI relee los parámetros de búsqueda cada cierto tiempo, no en cada escritura. Sin forzar el
 * refresco, las primeras búsquedas después de arrancar contestarían como si el parámetro no existiera
 * — que en un test es un fallo intermitente y en producción es peor: nadie se entera.
 */
@Component
public class ParametrosDeBusquedaPropios {

    private static final Logger LOG = LoggerFactory.getLogger(ParametrosDeBusquedaPropios.class);

    /** La lista es explícita porque cada parámetro es una decisión, no un fichero suelto. */
    private static final List<String> DE_LA_GUIA =
            List.of("conformidad/SearchParameter-notificacion-edo-vencimiento.json");

    private final FhirContext contexto;
    private final DaoRegistry daos;
    private final ISearchParamRegistry registro;

    public ParametrosDeBusquedaPropios(FhirContext contexto, DaoRegistry daos, ISearchParamRegistry registro) {
        this.contexto = contexto;
        this.daos = daos;
        this.registro = registro;
    }

    /**
     * Deja los parámetros publicados e indexando, con {@code PUT} e id fijo.
     *
     * <p>Idempotente por construcción: el id sale del recurso, así que arrancar cien veces deja uno.
     *
     * <p>Un fallo aquí <strong>no impide arrancar</strong>: sin el parámetro no se puede listar lo
     * vencido, que es grave, pero con la API caída no hay laboratorio. Se avisa fuerte y se sigue.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void publicar() {
        boolean alguno = false;
        for (String fichero : DE_LA_GUIA) {
            try {
                SearchParameter parametro = leer(fichero);
                daos.getResourceDao(SearchParameter.class).update(parametro, new SystemRequestDetails());
                alguno = true;
                LOG.info("Parámetro de búsqueda propio publicado: {} sobre {}.", parametro.getCode(), parametro
                        .getBase()
                        .getFirst()
                        .getCode());
            } catch (RuntimeException e) {
                LOG.error(
                        "No se ha podido publicar el parámetro de «{}», así que las búsquedas que dependan de él no "
                                + "encontrarán nada. El laboratorio arranca igual. Causa: {}",
                        fichero,
                        e.toString());
            }
        }
        if (alguno) {
            registro.forceRefresh();
        }
    }

    private SearchParameter leer(String fichero) {
        try (InputStream flujo = new ClassPathResource(fichero).getInputStream()) {
            return (SearchParameter)
                    contexto.newJsonParser().parseResource(new String(flujo.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException noSePuedeLeer) {
            throw new UncheckedIOException(noSePuedeLeer);
        }
    }
}
