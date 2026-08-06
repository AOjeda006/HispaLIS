package es.hispalis.backend.aplicacion.informe;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.informe.Informe;
import es.hispalis.backend.dominio.informe.LineaDeLaPeticion;
import es.hispalis.backend.dominio.informe.RepositorioDeInformes;
import es.hispalis.backend.dominio.peticion.Peticion;
import es.hispalis.backend.dominio.peticion.RepositorioDePeticiones;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.Referencias;
import es.hispalis.backend.fhir.informe.TraductorDeInforme;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hl7.fhir.r5.model.DiagnosticReport;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emisión del informe: el último paso del circuito.
 *
 * <p>Los resultados <strong>se cargan del dominio</strong>, no se toman del recurso recibido. Es la
 * misma razón que en {@code InformarResultado}: lo que un cliente diga sobre un resultado no es
 * fuente de verdad sobre ese resultado. Aquí además tiene una consecuencia concreta — así se
 * comprueba de verdad que todos son del mismo paciente, que es el peor error posible en un
 * laboratorio.
 *
 * <p>Y lo mismo con el <strong>alcance</strong>: el volante entero se reconstruye desde la base de
 * datos partiendo de las líneas que citan los resultados, no de lo que el informe diga traer. La
 * comprobación la hace el agregado; aquí solo se le entregan los hechos.
 */
@Service
public class EmitirInforme {

    private final RepositorioDeInformes informes;
    private final RepositorioDeResultados resultados;
    private final RepositorioDePeticiones peticiones;
    private final TraductorDeInforme traductor;
    private final DaoRegistry daos;

    public EmitirInforme(
            RepositorioDeInformes informes,
            RepositorioDeResultados resultados,
            RepositorioDePeticiones peticiones,
            TraductorDeInforme traductor,
            DaoRegistry daos) {
        this.informes = informes;
        this.resultados = resultados;
        this.peticiones = peticiones;
        this.traductor = traductor;
        this.daos = daos;
    }

    /**
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si el informe no lleva resultados
     *     o si el volante tiene líneas sin resolver
     * @throws DatoInvalido si algún resultado no existe o mezclan pacientes
     */
    @Transactional
    public DaoMethodOutcome ejecutar(DiagnosticReport recibido, RequestDetails peticionHttp) {
        List<Resultado> incluidos = recibido.getResult().stream()
                .map(EmitirInforme::identidadDelResultado)
                .map(this::cargar)
                .toList();

        Informe informe = Informe.emitir(
                incluidos,
                alcanceDe(incluidos),
                recibido.getPerformerFirstRep().getReference(),
                recibido.hasIssued() ? recibido.getIssued().toInstant() : null);
        informes.guardar(informe);

        return daos.getResourceDao(DiagnosticReport.class).update(traductor.aFhir(informe), peticionHttp);
    }

    /**
     * Reconstruye los volantes que tocan estos resultados, con el estado de cada una de sus líneas.
     *
     * <p>Va en dos saltos y el primero es el que importa: de las líneas que los resultados citan se
     * sube <strong>al número de volante</strong>, y de ahí se bajan <em>todas</em> sus líneas. Sin
     * ese rodeo solo se verían las líneas que ya tienen resultado, que son justo las que nunca
     * bloquean nada — el invariante quedaría siempre satisfecho y no habría forma de notarlo.
     */
    private List<LineaDeLaPeticion> alcanceDe(List<Resultado> incluidos) {
        List<UUID> citadas = incluidos.stream()
                .map(Resultado::peticionId)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        if (citadas.isEmpty()) {
            return List.of();
        }

        Set<String> volantes = citadas.stream()
                .map(this::cargarLinea)
                .map(Peticion::numeroDePeticion)
                .collect(Collectors.toSet());

        List<Peticion> lineas =
                peticiones.buscarLineasDeVolantes(volantes, incluidos.get(0).pacienteId());
        Set<UUID> resueltas =
                resultados.lineasConResultado(lineas.stream().map(Peticion::id).toList());

        return lineas.stream()
                .map(linea -> new LineaDeLaPeticion(
                        linea.id(),
                        linea.numeroDePeticion(),
                        linea.codigoDePrueba(),
                        resueltas.contains(linea.id()),
                        linea.estaAnulada()))
                .toList();
    }

    private static UUID identidadDelResultado(Reference referencia) {
        return Referencias.identidadDe(referencia, "resultado");
    }

    private Resultado cargar(UUID id) {
        return resultados
                .buscarPorId(id)
                .orElseThrow(() ->
                        new DatoInvalido("El resultado %s no está registrado en este laboratorio.".formatted(id)));
    }

    private Peticion cargarLinea(UUID id) {
        return peticiones
                .buscarPorId(id)
                .orElseThrow(() -> new DatoInvalido(
                        "La línea de petición %s no está registrada en este laboratorio.".formatted(id)));
    }
}
