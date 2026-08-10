package es.hispalis.backend.aplicacion.edo;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import org.hl7.fhir.r5.model.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * A quién declara este laboratorio, dado de alta como dato maestro.
 *
 * <p><strong>Simulado, y a propósito.</strong> El diseño (§15) fija que una integración inventada con
 * una administración real da falso realismo y no se puede validar. Lo que hay aquí es un destinatario
 * con la forma que tendría, no la unidad de protección de la salud de ningún distrito sanitario
 * concreto. Lo que sí es real es la obligación de declarar.
 *
 * <p><strong>Por qué el organismo tiene que existir como recurso.</strong> El {@code Task.owner} es una
 * referencia, y el servidor exige que las referencias resuelvan. Un {@code Task} apuntando a un
 * {@code Organization} que no está no se escribe, así que la declaración entera se caería por no tener
 * dado de alta al destinatario — que es un fallo de configuración disfrazado de fallo clínico.
 *
 * <p>Se da de alta con {@code PUT} e id fijo, así que es idempotente: arrancar cien veces deja una
 * organización y no cien. Es la misma mecánica que {@code TopicosDelLaboratorio}.
 */
public class DestinatarioDeLaDeclaracion {

    private static final Logger LOG = LoggerFactory.getLogger(DestinatarioDeLaDeclaracion.class);

    /** Id fijo. Que sea legible y no un UUID es deliberado: es dato maestro, no un caso. */
    private static final String ID = "salud-publica";

    private final DaoRegistry daos;
    private final String nombre;

    public DestinatarioDeLaDeclaracion(DaoRegistry daos, String nombre) {
        this.daos = daos;
        this.nombre = nombre;
    }

    /**
     * Deja el organismo publicado al arrancar.
     *
     * <p>Un fallo aquí <strong>no impide arrancar</strong>, pero sí deja al laboratorio sin poder
     * declarar, así que se avisa como error y no como aviso: es lo contrario del tópico de
     * notificación, que es una función de más. Aquí hay una obligación legal detrás.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void darDeAlta() {
        try {
            Organization organismo = new Organization();
            organismo.setId(ID);
            organismo.setName(nombre);
            organismo.setActive(true);
            daos.getResourceDao(Organization.class).update(organismo, new SystemRequestDetails());
            LOG.info("Destinatario de las declaraciones EDO dado de alta: {} ({}).", nombre, organismo());
        } catch (RuntimeException e) {
            LOG.error(
                    "No se ha podido dar de alta al destinatario de las declaraciones EDO, así que NO se podrán "
                            + "abrir declaraciones obligatorias. El laboratorio arranca igual. Causa: {}",
                    e.toString());
        }
    }

    /** La referencia al organismo de Salud Pública, para el {@code Task.owner}. */
    public String organismo() {
        return "Organization/" + ID;
    }
}
