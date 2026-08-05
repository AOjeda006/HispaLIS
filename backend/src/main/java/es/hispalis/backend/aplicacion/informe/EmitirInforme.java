package es.hispalis.backend.aplicacion.informe;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import es.hispalis.backend.dominio.DatoInvalido;
import es.hispalis.backend.dominio.informe.Informe;
import es.hispalis.backend.dominio.informe.RepositorioDeInformes;
import es.hispalis.backend.dominio.resultado.RepositorioDeResultados;
import es.hispalis.backend.dominio.resultado.Resultado;
import es.hispalis.backend.fhir.Referencias;
import es.hispalis.backend.fhir.informe.TraductorDeInforme;
import java.util.List;
import java.util.UUID;
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
 */
@Service
public class EmitirInforme {

    private final RepositorioDeInformes informes;
    private final RepositorioDeResultados resultados;
    private final TraductorDeInforme traductor;
    private final DaoRegistry daos;

    public EmitirInforme(
            RepositorioDeInformes informes,
            RepositorioDeResultados resultados,
            TraductorDeInforme traductor,
            DaoRegistry daos) {
        this.informes = informes;
        this.resultados = resultados;
        this.traductor = traductor;
        this.daos = daos;
    }

    /**
     * @throws es.hispalis.backend.dominio.ReglaDeNegocioIncumplida si el informe no lleva resultados
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
                List.of(),
                recibido.getPerformerFirstRep().getReference(),
                recibido.hasIssued() ? recibido.getIssued().toInstant() : null);
        informes.guardar(informe);

        return daos.getResourceDao(DiagnosticReport.class).update(traductor.aFhir(informe), peticionHttp);
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
}
