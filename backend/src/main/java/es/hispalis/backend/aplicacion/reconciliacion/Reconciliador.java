package es.hispalis.backend.aplicacion.reconciliacion;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import es.hispalis.backend.aplicacion.reconciliacion.Divergencia.Clase;
import es.hispalis.backend.dominio.especimen.RepositorioDeEspecimenes;
import es.hispalis.backend.dominio.informe.RepositorioDeInformes;
import es.hispalis.backend.dominio.paciente.RepositorioDePacientes;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.especimen.TraductorDeEspecimen;
import es.hispalis.backend.fhir.informe.TraductorDeInforme;
import es.hispalis.backend.fhir.paciente.TraductorDePaciente;
import es.hispalis.backend.fhir.peticion.TraductorDePeticion;
import es.hispalis.backend.fhir.resultado.TraductorDeProcedencia;
import es.hispalis.backend.fhir.resultado.TraductorDeResultado;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.DomainResource;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.ServiceRequest;
import org.hl7.fhir.r5.model.Specimen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recorre el dominio y regenera la proyección FHIR. La vía de recuperación oficial de §15.
 *
 * <h2>Por qué existe</h2>
 *
 * <p>La proyección se escribe en la misma transacción que el dominio (D3, §9), y eso quita de en
 * medio la causa más común de divergencia. Pero no todas: un fallo de mapeo publica un recurso que
 * no dice lo que dice el agregado, y la transacción confirma tan contenta. {@code ADR-0002} dejó
 * escrito que hacía falta esto, y §15 insiste en que sea una <strong>vía oficial y no un guion de
 * emergencia</strong>: versionada, con test, ejecutable sobre un subconjunto y capaz de decir qué va
 * a cambiar antes de cambiarlo.
 *
 * <h2>Qué mira</h2>
 *
 * <p>Los seis recursos que tienen un agregado detrás: {@code Patient}, {@code ServiceRequest},
 * {@code Specimen}, {@code Observation}, {@code DiagnosticReport} y el {@code Provenance} de la
 * firma. {@code Organization} y {@code Practitioner} quedan fuera <strong>a propósito</strong>: son
 * datos maestros que se escriben directos por la API y no hay agregado con el que compararlos, así
 * que un reconciliador que los mirase los daría todos por huérfanos y los borraría.
 *
 * <p>Busca las <strong>dos</strong> direcciones. Regenerar desde el dominio arregla lo que falta y
 * deja intacto lo que sobra, y lo que sobra —un recurso publicado sin agregado detrás— es
 * precisamente la forma que tenía el incidente del {@code Bundle transaction}: un dato clínico que
 * el laboratorio publica y del que no responde nadie.
 *
 * <h2>Qué le hace a los {@code ETag} de los clientes</h2>
 *
 * <p>Reescribir por la DAO sube el {@code versionId}, y con él caducan los {@code ETag} que
 * cualquier cliente tuviera de ese recurso: su siguiente {@code PUT} con {@code If-Match} recibirá un
 * {@code 412}. <strong>Se acepta, y se acepta porque solo pasa donde tiene que pasar:</strong> el
 * reconciliador escribe únicamente los recursos que divergen —para eso está el modo revisión—, así
 * que un cliente solo pierde su {@code ETag} si lo que tenía era una copia equivocada. Que se le
 * rechace la escritura es entonces lo correcto, no un daño colateral. Los recursos que ya cuadraban
 * conservan su versión: HAPI no crea versión nueva cuando el contenido no cambia.
 *
 * <h2>La transacción</h2>
 *
 * <p>Una pasada es una transacción: o se repara todo lo encontrado o no se repara nada. Sobre el
 * laboratorio entero eso es una transacción larga, y la respuesta no es trocearla por dentro —dejaría
 * reparaciones a medias sin que nadie sepa cuáles— sino ejecutarla por paciente. Para eso existe el
 * parámetro.
 */
@Service
public class Reconciliador {

    private static final Logger LOG = LoggerFactory.getLogger(Reconciliador.class);

    /**
     * Los recursos clínicos que cuelgan de un paciente y se pueden buscar por él.
     *
     * <p>{@code Patient} no está: no es sujeto de sí mismo. {@code Provenance} tampoco: se busca por
     * {@code target}, porque apunta al resultado y no a la persona.
     */
    private static final List<Class<? extends IBaseResource>> COLGADOS_DEL_PACIENTE =
            List.of(ServiceRequest.class, Specimen.class, Observation.class, DiagnosticReport.class);

    private final RepositorioDePacientes pacientes;
    private final RepositorioDePeticiones peticiones;
    private final RepositorioDeEspecimenes especimenes;
    private final RepositorioDeResultados resultados;
    private final RepositorioDeInformes informes;

    private final TraductorDePaciente aPaciente;
    private final TraductorDePeticion aPeticion;
    private final TraductorDeEspecimen aEspecimen;
    private final TraductorDeResultado aResultado;
    private final TraductorDeProcedencia aProcedencia;
    private final TraductorDeInforme aInforme;

    private final DaoRegistry daos;
    private final IParser paraComparar;

    @SuppressWarnings("java:S107") // Son los cinco repositorios y los seis traductores: no hay menos.
    public Reconciliador(
            RepositorioDePacientes pacientes,
            RepositorioDePeticiones peticiones,
            RepositorioDeEspecimenes especimenes,
            RepositorioDeResultados resultados,
            RepositorioDeInformes informes,
            TraductorDePaciente aPaciente,
            TraductorDePeticion aPeticion,
            TraductorDeEspecimen aEspecimen,
            TraductorDeResultado aResultado,
            TraductorDeProcedencia aProcedencia,
            TraductorDeInforme aInforme,
            DaoRegistry daos,
            FhirContext contexto) {
        this.pacientes = pacientes;
        this.peticiones = peticiones;
        this.especimenes = especimenes;
        this.resultados = resultados;
        this.informes = informes;
        this.aPaciente = aPaciente;
        this.aPeticion = aPeticion;
        this.aEspecimen = aEspecimen;
        this.aResultado = aResultado;
        this.aProcedencia = aProcedencia;
        this.aInforme = aInforme;
        this.daos = daos;
        this.paraComparar = contexto.newJsonParser();
    }

    /**
     * Compara y, si se le pide, repara.
     *
     * @param soloEstePaciente el paciente a revisar, o {@code null} para el laboratorio entero
     * @param aplicar {@code false} para solo mirar
     */
    @Transactional
    public InformeDeReconciliacion ejecutar(UUID soloEstePaciente, boolean aplicar) {
        List<UUID> aRecorrer = soloEstePaciente != null ? List.of(soloEstePaciente) : pacientes.todasLasIdentidades();

        List<Divergencia> encontradas = new ArrayList<>();
        for (UUID pacienteId : aRecorrer) {
            encontradas.addAll(reconciliarA(pacienteId, aplicar));
        }
        if (soloEstePaciente == null) {
            encontradas.addAll(pacientesSinAgregado(aRecorrer, aplicar));
        }

        // Se registra el recuento, nunca las identidades: un log con la lista de recursos de una
        // persona es un rastro de que esa persona pasó por el laboratorio.
        LOG.info(
                "Reconciliación de {} paciente(s): {} divergencia(s){}",
                aRecorrer.size(),
                encontradas.size(),
                aplicar ? ", reparadas" : " (solo revisión)");
        return new InformeDeReconciliacion(aplicar, encontradas);
    }

    private List<Divergencia> reconciliarA(UUID pacienteId, boolean aplicar) {
        Map<String, Resource> esperados = proyeccionQueDeberiaHaber(pacienteId);
        List<Divergencia> encontradas = new ArrayList<>();

        Map<String, IBaseResource> publicados = loQuePublicaLaProyeccion(pacienteId, esperados);

        for (Resource esperado : esperados.values()) {
            comparar(esperado, publicados.get(referenciaDe(esperado)), aplicar).ifPresent(encontradas::add);
        }
        encontradas.addAll(loQueSobra(publicados, esperados.keySet(), aplicar));
        return encontradas;
    }

    /** La proyección tal y como el dominio dice que tendría que estar, indexada por {@code Tipo/id}. */
    private Map<String, Resource> proyeccionQueDeberiaHaber(UUID pacienteId) {
        Map<String, Resource> esperados = new LinkedHashMap<>();

        pacientes.buscarPorId(pacienteId).map(aPaciente::aFhir).ifPresent(recurso -> anotar(esperados, recurso));
        peticiones.buscarDePaciente(pacienteId).forEach(linea -> anotar(esperados, aPeticion.aFhir(linea)));
        especimenes.buscarDePaciente(pacienteId).forEach(muestra -> anotar(esperados, aEspecimen.aFhir(muestra)));
        informes.buscarDePaciente(pacienteId).forEach(informe -> anotar(esperados, aInforme.aFhir(informe)));

        for (Resultado resultado : resultados.buscarDePaciente(pacienteId)) {
            anotar(esperados, aResultado.aFhir(resultado));
            // La procedencia no es un agregado: es la proyección de la firma que lleva dentro el
            // resultado. Su identidad se deriva de la del resultado justamente para que regenerarla
            // sobrescriba en vez de duplicar (ver `TraductorDeProcedencia.identidadDe`).
            if (resultado.validacion().isPresent()) {
                anotar(esperados, aProcedencia.aFhir(resultado));
            }
        }
        return esperados;
    }

    private static void anotar(Map<String, Resource> esperados, Resource recurso) {
        esperados.put(referenciaDe(recurso), recurso);
    }

    /**
     * Lo que la proyección publica de este paciente, indexado por {@code Tipo/id}.
     *
     * <p>Se obtiene <strong>buscando</strong> y no leyendo recurso a recurso, y no es cuestión del
     * número de consultas: la DAO de HAPI lanza {@code ResourceGoneException} al leer un recurso
     * borrado, y esa excepción sale de un método transaccional suyo, así que Spring marca la
     * transacción de esta pasada como <em>rollback-only</em> aunque aquí se capture. El reconciliador
     * se quedaba sin poder reparar justo el caso para el que existe. Una búsqueda no encuentra lo
     * borrado y no lanza nada.
     */
    private Map<String, IBaseResource> loQuePublicaLaProyeccion(UUID pacienteId, Map<String, Resource> esperados) {
        List<IBaseResource> publicados = new ArrayList<>();

        SearchParameterMap esteMismo = SearchParameterMap.newSynchronous();
        esteMismo.add("_id", new TokenParam(pacienteId.toString()));
        publicados.addAll(buscar(Patient.class, esteMismo));

        for (Class<? extends IBaseResource> tipo : COLGADOS_DEL_PACIENTE) {
            SearchParameterMap delPaciente = SearchParameterMap.newSynchronous();
            delPaciente.add("subject", new ReferenceParam("Patient/" + pacienteId));
            publicados.addAll(buscar(tipo, delPaciente));
        }
        publicados.addAll(procedenciasDe(publicados, esperados.keySet()));

        Map<String, IBaseResource> porReferencia = new LinkedHashMap<>();
        publicados.forEach(publicado -> porReferencia.put(referenciaDe((Resource) publicado), publicado));
        return porReferencia;
    }

    private Optional<Divergencia> comparar(Resource esperado, IBaseResource publicado, boolean aplicar) {
        String tipo = esperado.fhirType();
        UUID id = UUID.fromString(esperado.getIdElement().getIdPart());

        Clase clase;
        if (publicado == null) {
            clase = Clase.AUSENTE;
        } else if (dicenLoMismo(esperado, publicado)) {
            return Optional.empty();
        } else {
            clase = Clase.DISTINTO;
        }

        if (aplicar) {
            daoDe(tipo).update(esperado, new SystemRequestDetails());
        }
        return Optional.of(new Divergencia(tipo, id, clase));
    }

    /** Lo publicado que no tiene agregado detrás. */
    private List<Divergencia> loQueSobra(
            Map<String, IBaseResource> publicados, Set<String> esperados, boolean aplicar) {
        List<Divergencia> sobrantes = new ArrayList<>();
        for (Map.Entry<String, IBaseResource> entrada : publicados.entrySet()) {
            if (esperados.contains(entrada.getKey())) {
                continue;
            }
            IBaseResource publicado = entrada.getValue();
            String tipo = publicado.fhirType();
            if (aplicar) {
                daoDe(tipo).delete(publicado.getIdElement().toUnqualifiedVersionless(), new SystemRequestDetails());
            }
            sobrantes.add(new Divergencia(
                    tipo, UUID.fromString(publicado.getIdElement().getIdPart()), Clase.HUERFANO));
        }
        return sobrantes;
    }

    /**
     * Las procedencias que apuntan a los resultados encontrados.
     *
     * <p>Una procedencia no se puede buscar por paciente —apunta al resultado, no a la persona—, así
     * que se busca por {@code target}. La consecuencia, dicha para que no se descubra tarde: una
     * procedencia que apuntase a un resultado que ya no existe no aparece en el recorrido por
     * paciente. Es un huérfano de un huérfano y no lo produce ningún camino de este sistema; si algún
     * día hace falta cazarlo, es un barrido aparte.
     */
    private List<IBaseResource> procedenciasDe(Collection<IBaseResource> publicados, Set<String> esperados) {
        Set<String> resultados = new LinkedHashSet<>();
        publicados.stream()
                .filter(Observation.class::isInstance)
                .forEach(observacion -> resultados.add(referenciaDe((Resource) observacion)));
        // También los que el dominio espera y la proyección no tiene: si falta el resultado pero su
        // firma sigue publicada, la firma es un huérfano y hay que verlo.
        esperados.stream()
                .filter(referencia -> referencia.startsWith("Observation/"))
                .forEach(resultados::add);

        ReferenceOrListParam objetivos = new ReferenceOrListParam();
        resultados.forEach(referencia -> objetivos.add(new ReferenceParam(referencia)));

        if (objetivos.getValuesAsQueryTokens().isEmpty()) {
            return List.of();
        }
        SearchParameterMap deEsosResultados = SearchParameterMap.newSynchronous();
        deEsosResultados.add("target", objetivos);
        return buscar(Provenance.class, deEsosResultados);
    }

    /** Los {@code Patient} publicados que ya no tienen paciente detrás. Solo en el recorrido completo. */
    private List<Divergencia> pacientesSinAgregado(List<UUID> enElDominio, boolean aplicar) {
        Set<String> conocidos = enElDominio.stream().map(id -> "Patient/" + id).collect(Collectors.toSet());

        List<Divergencia> sobrantes = new ArrayList<>();
        for (IBaseResource publicado : todosLosDeTipo(Patient.class)) {
            String referencia = referenciaDe((Resource) publicado);
            if (conocidos.contains(referencia)) {
                continue;
            }
            if (aplicar) {
                daoDe("Patient")
                        .delete(publicado.getIdElement().toUnqualifiedVersionless(), new SystemRequestDetails());
            }
            sobrantes.add(new Divergencia(
                    "Patient", UUID.fromString(publicado.getIdElement().getIdPart()), Clase.HUERFANO));
        }
        return sobrantes;
    }

    // ── Hablar con la proyección ─────────────────────────────────────────────────────────────────

    /**
     * Busca en la proyección, siempre en modo <strong>síncrono</strong>.
     *
     * <p>{@code newSynchronous()} no es un detalle de rendimiento: evita el camino de búsquedas
     * paginadas y cacheadas de HAPI, que es el que convirtió la idempotencia en una ilusión en
     * {@code ADR-0019}. Un reconciliador que comparase contra un resultado cacheado inventaría
     * divergencias o, peor, dejaría de ver las que hay.
     */
    private List<IBaseResource> buscar(Class<? extends IBaseResource> tipo, SearchParameterMap consulta) {
        return daos.getResourceDao(tipo)
                .search(consulta, new SystemRequestDetails())
                .getAllResources();
    }

    private List<IBaseResource> todosLosDeTipo(Class<? extends IBaseResource> tipo) {
        return buscar(tipo, SearchParameterMap.newSynchronous());
    }

    private IFhirResourceDao<IBaseResource> daoDe(String tipo) {
        return daos.getResourceDaoOrNull(tipo);
    }

    // ── Comparar ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Compara el contenido, no los metadatos.
     *
     * <p>La versión y la fecha de última modificación son del servidor y cambian en cada escritura:
     * incluirlas haría que todo divergiera siempre. El perfil sí se compara —lo pone el traductor y
     * forma parte de lo que el laboratorio publica—, y la narrativa se descarta porque es
     * presentación derivada, no dato.
     */
    private boolean dicenLoMismo(Resource esperado, IBaseResource publicado) {
        return normalizado(esperado).equals(normalizado((Resource) publicado));
    }

    private String normalizado(Resource recurso) {
        Resource copia = recurso.copy();
        copia.setId(copia.getIdElement().getIdPart());
        copia.getMeta().setVersionId(null);
        copia.getMeta().setLastUpdated(null);
        copia.getMeta().setSource(null);
        copia.getMeta().getTag().clear();
        if (copia instanceof DomainResource conNarrativa) {
            conNarrativa.setText(null);
        }
        return paraComparar.encodeResourceToString(copia);
    }

    private static String referenciaDe(Resource recurso) {
        return recurso.fhirType() + "/" + recurso.getIdElement().getIdPart();
    }
}
