package es.hispalis.backend.fhir;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.provider.JpaCapabilityStatementProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.util.FhirTerser;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r5.model.CapabilityStatement;
import org.hl7.fhir.r5.model.CapabilityStatement.CapabilityStatementRestResourceComponent;

/**
 * El {@code CapabilityStatement} de HispaLIS: el de HAPI, con los perfiles que de verdad soporta.
 *
 * <p>HAPI ya declara solo las <em>interacciones</em> que el servidor sabe hacer, porque las deduce
 * de los proveedores registrados en vez de leerlas de una lista escrita a mano. Con los perfiles
 * hace lo contrario: rellena {@code supportedProfile} con <strong>todos los que conoce</strong>, que
 * son los del núcleo de R5 al completo —{@code lipidprofile}, {@code clinicaldocument},
 * {@code cqllibrary}…—. Ese valor por defecto es una afirmación falsa: este servidor no los conoce,
 * no los exige y no valida contra ellos.
 *
 * <p>Lo que HispaLIS soporta son los perfiles de su guía, y eso es lo único que declara. Un
 * {@code CapabilityStatement} equivocado es peor que no tenerlo: es la única forma que tiene un
 * cliente de descubrir el contrato sin preguntar, y si miente, miente donde nadie va a dudar de él.
 */
public class ConformidadHispaLis extends JpaCapabilityStatementProvider {

    public ConformidadHispaLis(
            RestfulServer servidor,
            IFhirSystemDao<?, ?> systemDao,
            JpaStorageSettings ajustes,
            ISearchParamRegistry parametrosDeBusqueda,
            IValidationSupport soporteDeValidacion) {
        super(servidor, systemDao, ajustes, parametrosDeBusqueda, soporteDeValidacion);
        setImplementationDescription("HispaLIS — Sistema de Información de Laboratorio (simulación)");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Se corrige aquí y no en {@code postProcessRestResource} porque HAPI rellena los perfiles
     * <strong>después</strong> de recorrer los recursos: hacerlo antes no borraría nada.
     */
    @Override
    protected void postProcess(FhirTerser terser, IBaseConformance conformidad) {
        super.postProcess(terser, conformidad);

        // El cast es seguro y deliberado: este servidor es R5 y solo R5 (D1). Usar el `terser` para
        // ser agnóstico de versión sería pagar indirección por una portabilidad que nadie quiere.
        if (conformidad instanceof CapabilityStatement declaracion) {
            declaracion.getRest().stream()
                    .flatMap(rest -> rest.getResource().stream())
                    .forEach(ConformidadHispaLis::declararSoloLosPerfilesDeLaGuia);
        }
    }

    private static void declararSoloLosPerfilesDeLaGuia(CapabilityStatementRestResourceComponent recurso) {
        recurso.getSupportedProfile().clear();
        PerfilesDeLaGuia.deTipo(recurso.getType()).forEach(perfil -> recurso.addSupportedProfile(perfil.canonica()));
    }
}
