package es.hispalis.backend.aplicacion.especimen;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.RepositorioDeEspecimenes;
import es.hispalis.backend.fhir.especimen.TraductorDeEspecimen;
import org.hl7.fhir.r5.model.Specimen;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recepción de una muestra en el laboratorio.
 *
 * <p>Mismo patrón que el alta de paciente: traducir, validar en el agregado, guardar el dominio y
 * proyectar — todo en un solo {@code @Transactional} (§9 del diseño, ADR-0012).
 */
@Service
public class RegistrarEspecimen {

    private final RepositorioDeEspecimenes repositorio;
    private final TraductorDeEspecimen traductor;
    private final DaoRegistry daos;

    public RegistrarEspecimen(RepositorioDeEspecimenes repositorio, TraductorDeEspecimen traductor, DaoRegistry daos) {
        this.repositorio = repositorio;
        this.traductor = traductor;
        this.daos = daos;
    }

    @Transactional
    public DaoMethodOutcome ejecutar(Specimen recibido, RequestDetails peticion) {
        Especimen especimen = traductor.aDominio(recibido);
        repositorio.guardar(especimen);

        return daos.getResourceDao(Specimen.class).update(traductor.aFhir(especimen), peticion);
    }
}
