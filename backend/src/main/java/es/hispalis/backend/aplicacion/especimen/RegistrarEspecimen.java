package es.hispalis.backend.aplicacion.especimen;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.RepositorioDeEspecimenes;
import es.hispalis.backend.dominio.hecho.Hecho;
import es.hispalis.backend.dominio.hecho.RepositorioDeHechos;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import es.hispalis.backend.fhir.especimen.TraductorDeEspecimen;
import java.util.Map;
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
    private final RepositorioDeHechos hechos;
    private final TraductorDeEspecimen traductor;
    private final DaoRegistry daos;

    public RegistrarEspecimen(
            RepositorioDeEspecimenes repositorio,
            RepositorioDeHechos hechos,
            TraductorDeEspecimen traductor,
            DaoRegistry daos) {
        this.repositorio = repositorio;
        this.hechos = hechos;
        this.traductor = traductor;
        this.daos = daos;
    }

    @Transactional
    public DaoMethodOutcome ejecutar(Specimen recibido, RequestDetails peticion) {
        Especimen especimen = traductor.aDominio(recibido);
        repositorio.guardar(especimen);
        hechos.registrar(Hecho.de(
                TipoDeHecho.ESPECIMEN_REGISTRADO,
                especimen.pacienteId(),
                Map.of("specimenRef", "Specimen/" + especimen.id())));

        return daos.getResourceDao(Specimen.class).update(traductor.aFhir(especimen), peticion);
    }
}
