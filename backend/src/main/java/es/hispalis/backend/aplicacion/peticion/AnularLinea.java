package es.hispalis.backend.aplicacion.peticion;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.fhir.peticion.TraductorDePeticion;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Enumerations.RequestStatus;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anulación de una línea de petición: retirar una prueba que el laboratorio no va a hacer.
 *
 * <p>Es la <strong>única</strong> modificación soportada de un {@code ServiceRequest}, y por eso el
 * {@code PUT} entra por aquí en vez de seguir rechazado en bloque. Cualquier otro cambio se sigue
 * rechazando con un error explícito: es la regla de {@code ADR-0014} —lo que no tiene reglas de
 * negocio definidas se rechaza— y la alternativa no es «que funcione», sino que el {@code update}
 * heredado de HAPI escriba la proyección y deje el dominio atrás sin un solo aviso.
 *
 * <p>Lo que <strong>no</strong> se hace aquí es decidir si la anulación procede. Eso lo decide el
 * agregado {@link Peticion#anular}, que es lo que hace que la regla valga igual cuando el hito 2
 * traiga la segunda puerta de entrada — el motor de integración.
 */
@Service
public class AnularLinea {

    private final RepositorioDePeticiones peticiones;
    private final RepositorioDeResultados resultados;
    private final TraductorDePeticion traductor;
    private final DaoRegistry daos;

    public AnularLinea(
            RepositorioDePeticiones peticiones,
            RepositorioDeResultados resultados,
            TraductorDePeticion traductor,
            DaoRegistry daos) {
        this.peticiones = peticiones;
        this.resultados = resultados;
        this.traductor = traductor;
        this.daos = daos;
    }

    /**
     * @param identidad el id del recurso, con la versión del {@code If-Match} si venía
     * @param recibido el recurso tal y como llegó; de él solo se leen el estado y la nota
     * @throws ReglaDeNegocioIncumplida si se pide cualquier modificación que no sea anular, si la
     *     línea ya estaba anulada o si ya tiene resultados
     * @throws DatoInvalido si la línea no existe o si no se da motivo
     */
    @Transactional
    public DaoMethodOutcome ejecutar(IIdType identidad, ServiceRequest recibido, RequestDetails peticionHttp) {
        exigirQueSoloPretendaAnular(recibido);

        UUID id = identidadDe(identidad);
        Peticion linea = peticiones
                .buscarPorId(id)
                .orElseThrow(() -> new DatoInvalido(
                        "La línea de petición %s no está registrada en este laboratorio.".formatted(id)));

        boolean yaTieneResultados = !resultados.lineasConResultado(List.of(id)).isEmpty();
        Peticion anulada = linea.anular(yaTieneResultados, motivoDe(recibido), null);
        peticiones.actualizar(anulada);

        ServiceRequest proyeccion = traductor.aFhir(anulada);
        // Se conserva la versión que traía el `If-Match`: es lo que permite a la DAO detectar que
        // otro escribió mientras tanto y responder 412 en vez de pisarlo.
        proyeccion.setId(identidad.getValue());
        return daos.getResourceDao(ServiceRequest.class).update(proyeccion, peticionHttp);
    }

    /**
     * El {@code PUT} sirve para anular y para nada más.
     *
     * <p>Se mira el estado que trae el recurso y no se compara el resto contra la proyección: un
     * cliente que mande el recurso entero con un campo cambiado y el estado en {@code revoked}
     * consigue una anulación y nada más, porque la proyección se regenera desde el dominio y el
     * dominio solo cambia de estado. No hay forma de colar una modificación por este camino.
     */
    private static void exigirQueSoloPretendaAnular(ServiceRequest recibido) {
        if (recibido.getStatus() != RequestStatus.REVOKED) {
            throw new ReglaDeNegocioIncumplida(
                    "De una línea de petición ya registrada solo se puede anular: mándala con "
                            + "`status = revoked` y una nota con el motivo. Cambiar cualquier otra cosa no tiene "
                            + "reglas de negocio definidas, y se rechaza en vez de escribir solo la mitad.");
        }
    }

    private static String motivoDe(ServiceRequest recibido) {
        return recibido.hasNote() ? recibido.getNoteFirstRep().getText() : null;
    }

    private static UUID identidadDe(IIdType identidad) {
        try {
            return UUID.fromString(identidad.getIdPart());
        } catch (IllegalArgumentException e) {
            throw new DatoInvalido(
                    "«%s» no es una línea de petición de este laboratorio.".formatted(identidad.getIdPart()));
        }
    }
}
