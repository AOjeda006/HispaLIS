package es.hispalis.backend.aplicacion.peticion;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.hecho.Hecho;
import es.hispalis.backend.dominio.hecho.RepositorioDeHechos;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import es.hispalis.backend.fhir.peticion.TraductorDePeticion;
import java.util.Map;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registro de una línea de petición analítica. Mismo patrón que el resto (§9 del diseño, ADR-0012). */
@Service
public class RegistrarPeticion {

    private final RepositorioDePeticiones repositorio;
    private final RepositorioDeHechos hechos;
    private final TraductorDePeticion traductor;
    private final DaoRegistry daos;

    public RegistrarPeticion(
            RepositorioDePeticiones repositorio,
            RepositorioDeHechos hechos,
            TraductorDePeticion traductor,
            DaoRegistry daos) {
        this.repositorio = repositorio;
        this.hechos = hechos;
        this.traductor = traductor;
        this.daos = daos;
    }

    @Transactional
    public DaoMethodOutcome ejecutar(ServiceRequest recibido, RequestDetails peticionHttp) {
        Peticion peticion = traductor.aDominio(recibido);
        repositorio.guardar(peticion);
        hechos.registrar(Hecho.de(
                TipoDeHecho.PETICION_REGISTRADA,
                peticion.pacienteId(),
                Map.of("serviceRequestRef", "ServiceRequest/" + peticion.id())));

        return daos.getResourceDao(ServiceRequest.class).update(traductor.aFhir(peticion), peticionHttp);
    }
}
