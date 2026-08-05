package es.hispalis.backend.aplicacion.paciente;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.paciente.Paciente;
import es.hispalis.backend.dominio.paciente.RepositorioDePacientes;
import es.hispalis.backend.fhir.paciente.TraductorDePaciente;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Patient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrección de la filiación de un paciente: un apellido mal escrito, un DNI que faltaba.
 *
 * <p>Existe <strong>porque si no existiera, el {@code PUT} heredado de HAPI escribiría la proyección
 * y dejaría el dominio atrás</strong>, en silencio y sin un solo error. Es la forma más fácil de que
 * las dos mitades se separen, y una vez separadas nada avisa.
 *
 * <p>La concurrencia optimista la resuelve HAPI: el {@code If-Match} llega como versión dentro del
 * {@code IIdType}, se traslada al recurso proyectado, y la DAO responde {@code 412} si esa versión
 * ya no es la vigente. El dominio no sabe de versiones —no es su problema—, pero <strong>al ir todo
 * en la misma transacción, un 412 revierte también la escritura del dominio</strong>.
 */
@Service
public class ActualizarPaciente {

    private final RepositorioDePacientes repositorio;
    private final TraductorDePaciente traductor;
    private final DaoRegistry daos;

    public ActualizarPaciente(RepositorioDePacientes repositorio, TraductorDePaciente traductor, DaoRegistry daos) {
        this.repositorio = repositorio;
        this.traductor = traductor;
        this.daos = daos;
    }

    /**
     * @param identidad el id del recurso, con la versión del {@code If-Match} si venía
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si se intenta cambiar el NHC
     * @throws DatoInvalido si el paciente no existe
     */
    @Transactional
    public DaoMethodOutcome ejecutar(IIdType identidad, Patient recibido, RequestDetails peticion) {
        UUID id = identidadDe(identidad);
        Paciente existente = repositorio
                .buscarPorId(id)
                .orElseThrow(
                        () -> new DatoInvalido("El paciente %s no está registrado en este laboratorio.".formatted(id)));

        Paciente actualizado = traductor.aplicarSobre(existente, recibido);
        repositorio.actualizar(actualizado);

        Patient proyeccion = traductor.aFhir(actualizado);
        // Se conserva la versión que traía el `If-Match`: es lo que hace que la DAO pueda detectar
        // que otro ya escribió mientras tanto.
        proyeccion.setId(identidad.getValue());
        return daos.getResourceDao(Patient.class).update(proyeccion, peticion);
    }

    private static UUID identidadDe(IIdType identidad) {
        try {
            return UUID.fromString(identidad.getIdPart());
        } catch (IllegalArgumentException e) {
            throw new DatoInvalido(
                    "«%s» no es un identificador de paciente de este laboratorio.".formatted(identidad.getIdPart()));
        }
    }
}
