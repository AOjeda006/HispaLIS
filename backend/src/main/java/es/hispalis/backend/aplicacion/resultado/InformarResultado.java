package es.hispalis.backend.aplicacion.resultado;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.RepositorioDeEspecimenes;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.Referencias;
import es.hispalis.backend.fhir.resultado.TraductorDeResultado;
import java.util.UUID;
import org.hl7.fhir.r5.model.Observation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Informar un resultado analítico: el caso de uso donde se aplica el invariante de C6.
 *
 * <p>La muestra <strong>se carga del dominio</strong>, no se cree lo que diga el recurso recibido.
 * Es la diferencia entre comprobar un invariante y aparentar que se comprueba: un cliente puede
 * mandar un {@code Observation} que referencie una muestra rechazada sin mencionar su estado —o
 * mintiendo sobre él—, y la única fuente fiable es el propio laboratorio.
 *
 * <p>La comprobación la hace el agregado {@link Resultado} al construirse, no este servicio. Aquí
 * solo se le entrega la muestra de verdad.
 */
@Service
public class InformarResultado {

    private final RepositorioDeEspecimenes especimenes;
    private final RepositorioDeResultados resultados;
    private final TraductorDeResultado traductor;
    private final DaoRegistry daos;

    public InformarResultado(
            RepositorioDeEspecimenes especimenes,
            RepositorioDeResultados resultados,
            TraductorDeResultado traductor,
            DaoRegistry daos) {
        this.especimenes = especimenes;
        this.resultados = resultados;
        this.traductor = traductor;
        this.daos = daos;
    }

    /**
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si la muestra fue rechazada
     * @throws DatoInvalido si la muestra referenciada no existe o falta el valor
     */
    @Transactional
    public DaoMethodOutcome ejecutar(Observation recibido, RequestDetails peticion) {
        UUID especimenId = Referencias.identidadDe(recibido.getSpecimen(), "muestra");
        Especimen especimen = especimenes
                .buscarPorId(especimenId)
                .orElseThrow(() -> new DatoInvalido(
                        "La muestra %s no está registrada en este laboratorio.".formatted(especimenId)));

        // `basedOn` es opcional: una repetición de control o una determinación añadida en el
        // laboratorio existen aunque nadie las pidiera por volante.
        UUID peticionId =
                recibido.hasBasedOn() ? Referencias.identidadDe(recibido.getBasedOnFirstRep(), "petición") : null;

        Resultado resultado = traductor.aDominio(recibido, especimen, peticionId);
        resultados.guardar(resultado);

        return daos.getResourceDao(Observation.class).update(traductor.aFhir(resultado), peticion);
    }
}
