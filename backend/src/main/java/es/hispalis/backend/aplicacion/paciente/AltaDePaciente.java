package es.hispalis.backend.aplicacion.paciente;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.paciente.Paciente;
import es.hispalis.backend.dominio.paciente.RepositorioDePacientes;
import es.hispalis.backend.fhir.paciente.TraductorDePaciente;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta de un paciente: el caso de uso donde vive la transacción única.
 *
 * <p>Los tres pasos ocurren <strong>dentro de un solo {@code @Transactional}</strong> y ese es el
 * punto entero de la arquitectura (D3, §9 del diseño):
 *
 * <ol>
 *   <li>el recurso recibido se traduce al agregado, que <strong>valida sus invariantes al
 *       construirse</strong>: no llega a existir un paciente inválido;
 *   <li>el agregado se guarda en el esquema {@code dominio}, que es la fuente de verdad;
 *   <li>la proyección FHIR se <strong>genera desde el agregado</strong> y se escribe con las DAO de
 *       HAPI, que de paso pueblan sus índices de búsqueda.
 * </ol>
 *
 * <p>Si la proyección fuese asíncrona —un evento, una cola—, el {@code GET} inmediato al
 * {@code Location} del {@code 201} devolvería {@code 404}. Eso no es una latencia aceptable: es
 * <strong>incumplir FHIR REST</strong>. Y al revés: si el dominio rechaza el alta, la transacción
 * revierte entera y no queda un recurso FHIR huérfano al que nada respalda.
 *
 * <p>El {@code JpaTransactionManager} tiene fijado el {@code DataSource}, así que el SQL del
 * repositorio y las DAO de HAPI comparten conexión y por tanto transacción. Sin eso, serían dos
 * transacciones distintas y todo lo anterior sería falso.
 */
@Service
public class AltaDePaciente {

    private final RepositorioDePacientes repositorio;
    private final TraductorDePaciente traductor;
    private final DaoRegistry daos;

    public AltaDePaciente(RepositorioDePacientes repositorio, TraductorDePaciente traductor, DaoRegistry daos) {
        this.repositorio = repositorio;
        this.traductor = traductor;
        this.daos = daos;
    }

    /**
     * Da de alta al paciente y devuelve el resultado de escribir su proyección.
     *
     * @param recibido el {@code Patient} tal y como llegó por la API
     * @param peticion contexto de la petición, que HAPI necesita para auditar y componer el
     *     {@code Location}
     * @return el resultado de HAPI, con el id asignado y la versión
     * @throws es.hispalis.backend.dominio.ErrorDeDominio si el paciente incumple un invariante
     */
    @Transactional
    public DaoMethodOutcome ejecutar(Patient recibido, RequestDetails peticion) {
        Paciente paciente = traductor.aDominio(recibido);
        repositorio.guardar(paciente);

        return daos.getResourceDao(Patient.class).create(traductor.aFhir(paciente), peticion);
    }
}
