package es.hispalis.backend.aplicacion.resultado;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.especimen.Especimen;
import es.hispalis.backend.dominio.especimen.RepositorioDeEspecimenes;
import es.hispalis.backend.dominio.hecho.Hecho;
import es.hispalis.backend.dominio.hecho.RepositorioDeHechos;
import es.hispalis.backend.dominio.hecho.TipoDeHecho;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import es.hispalis.backend.dominio.resultado.CatalogoDeRangosDeReferencia;
import es.hispalis.backend.dominio.resultado.ReglaRefleja;
import es.hispalis.backend.dominio.resultado.ReglasReflejas;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.Referencias;
import es.hispalis.backend.fhir.peticion.TraductorDePeticion;
import es.hispalis.backend.fhir.resultado.TraductorDeResultado;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Reference;
import org.hl7.fhir.r5.model.ServiceRequest;
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
    private final RepositorioDePeticiones peticiones;
    private final RepositorioDeResultados resultados;
    private final RepositorioDeHechos hechos;
    private final TraductorDeResultado traductor;
    private final TraductorDePeticion traductorDePeticion;
    private final ReglasReflejas reglasReflejas;
    private final CatalogoDeRangosDeReferencia rangos;
    private final DaoRegistry daos;

    public InformarResultado(
            RepositorioDeEspecimenes especimenes,
            RepositorioDePeticiones peticiones,
            RepositorioDeResultados resultados,
            RepositorioDeHechos hechos,
            TraductorDeResultado traductor,
            TraductorDePeticion traductorDePeticion,
            ReglasReflejas reglasReflejas,
            CatalogoDeRangosDeReferencia rangos,
            DaoRegistry daos) {
        this.especimenes = especimenes;
        this.peticiones = peticiones;
        this.resultados = resultados;
        this.hechos = hechos;
        this.traductor = traductor;
        this.traductorDePeticion = traductorDePeticion;
        this.reglasReflejas = reglasReflejas;
        this.rangos = rangos;
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
        // laboratorio existen aunque nadie las pidiera por volante. Cuando sí viene, la línea se
        // CARGA del dominio y no se toma la referencia tal cual: es la misma razón que con la
        // muestra —lo que el cliente diga de una línea no es fuente de verdad sobre esa línea— y es
        // lo que permite que la fábrica del resultado compruebe que no está anulada.
        Peticion linea = recibido.hasBasedOn() ? cargarLinea(recibido.getBasedOnFirstRep()) : null;

        Resultado resultado = traductor.aDominio(recibido, especimen, linea);
        resultados.guardar(resultado);
        hechos.registrar(Hecho.de(TipoDeHecho.RESULTADO_INFORMADO, resultado.pacienteId(), referenciasDe(resultado)));

        DaoMethodOutcome publicado =
                daos.getResourceDao(Observation.class).update(traductor.aFhir(resultado), peticion);
        anadirLaReflejaSiProcede(resultado, linea, peticion);
        return publicado;
    }

    /**
     * La prueba refleja: el laboratorio añade una determinación que nadie pidió.
     *
     * <p><strong>Quién decide qué:</strong> el catálogo dice <em>qué</em> se añade
     * ({@code prueba-refleja}) y con qué palabras se cuenta ({@code motivo-de-la-refleja}); el
     * dominio dice <em>cuándo</em>, que es cuando la cifra se sale del rango que publica este
     * laboratorio ({@link Resultado#estaFueraDeRango}). Aquí no hay ni un código de prueba escrito:
     * cambiar a qué prueba refleja la TSH es cambiar el catálogo, no desplegar esto.
     *
     * <p>Va en la <strong>misma transacción</strong> que el resultado que la dispara, y tiene que
     * ir: una TSH alterada guardada sin su línea de T4 libre es una prueba que el laboratorio se
     * comprometió a hacer y de la que no queda constancia.
     *
     * <p>Y es <strong>idempotente</strong>. Reinformar la misma TSH —que pasa: el motor de
     * integración reprocesa un mensaje, alguien reenvía— no puede ir añadiendo una T4 libre en cada
     * vuelta. Se comprueba contra el volante, no contra el resultado.
     */
    private void anadirLaReflejaSiProcede(Resultado resultado, Peticion linea, RequestDetails peticionHttp) {
        // Sin línea no hay volante del que colgar la refleja ni a quién devolverle el resultado
        // añadido. Es una limitación deliberada: ver `Peticion.refleja`.
        if (linea == null) {
            return;
        }
        Optional<ReglaRefleja> regla = reglasReflejas.reflejaDe(resultado.codigoDePrueba());
        if (regla.isEmpty() || !resultado.estaFueraDeRango(rangos.buscarPorPrueba(resultado.codigoDePrueba()))) {
            return;
        }
        ReglaRefleja refleja = regla.get();
        if (peticiones.yaPedidaEnElVolante(linea.numeroDePeticion(), resultado.pacienteId(), refleja.codigoReflejo())) {
            return;
        }

        Peticion anadida = Peticion.refleja(linea, refleja.codigoReflejo(), resultado.id(), refleja.motivo());
        peticiones.guardar(anadida);
        hechos.registrar(Hecho.de(
                TipoDeHecho.PETICION_REGISTRADA,
                anadida.pacienteId(),
                Map.of(
                        "serviceRequestRef",
                        "ServiceRequest/" + anadida.id(),
                        "observationRef",
                        "Observation/" + resultado.id())));
        daos.getResourceDao(ServiceRequest.class).update(traductorDePeticion.aFhir(anadida), peticionHttp);
    }

    /** La cifra NO va en el hecho: es dato clínico. Va dónde mirarla, y eso ya pasa por la API. */
    private static Map<String, String> referenciasDe(Resultado resultado) {
        Map<String, String> referencias = new LinkedHashMap<>();
        referencias.put("observationRef", "Observation/" + resultado.id());
        referencias.put("specimenRef", "Specimen/" + resultado.especimenId());
        resultado.peticionId().ifPresent(linea -> referencias.put("serviceRequestRef", "ServiceRequest/" + linea));
        return referencias;
    }

    private Peticion cargarLinea(Reference referencia) {
        UUID id = Referencias.identidadDe(referencia, "petición");
        return peticiones
                .buscarPorId(id)
                .orElseThrow(() -> new DatoInvalido(
                        "La línea de petición %s no está registrada en este laboratorio.".formatted(id)));
    }
}
