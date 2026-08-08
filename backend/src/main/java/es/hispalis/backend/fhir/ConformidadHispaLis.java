package es.hispalis.backend.fhir;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.provider.JpaCapabilityStatementProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.util.FhirTerser;
import es.hispalis.backend.fhir.seguridad.DondeSeAutoriza;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r5.model.CapabilityStatement;
import org.hl7.fhir.r5.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r5.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r5.model.CapabilityStatement.CapabilityStatementRestSecurityComponent;
import org.hl7.fhir.r5.model.CapabilityStatement.SystemRestfulInteraction;
import org.hl7.fhir.r5.model.Extension;
import org.hl7.fhir.r5.model.UriType;

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

    /** La extensión con la que SMART publica las direcciones del servidor de autorización. */
    private static final String OAUTH_URIS = "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris";

    private static final String SERVICIO_DE_SEGURIDAD =
            "http://terminology.hl7.org/CodeSystem/restful-security-service";

    private final DondeSeAutoriza dondeSeAutoriza;

    public ConformidadHispaLis(
            RestfulServer servidor,
            IFhirSystemDao<?, ?> systemDao,
            JpaStorageSettings ajustes,
            ISearchParamRegistry parametrosDeBusqueda,
            IValidationSupport soporteDeValidacion,
            DondeSeAutoriza dondeSeAutoriza) {
        super(servidor, systemDao, ajustes, parametrosDeBusqueda, soporteDeValidacion);
        this.dondeSeAutoriza = dondeSeAutoriza;
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
            declaracion.getRest().forEach(ConformidadHispaLis::noPrometerTransacciones);
            declaracion.getRest().forEach(this::declararLaSeguridad);
            declaracion.getRest().stream()
                    .flatMap(rest -> rest.getResource().stream())
                    .forEach(ConformidadHispaLis::declararSoloLosPerfilesDeLaGuia);
        }
    }

    /**
     * Declara que este servidor se autoriza con SMART, y dónde.
     *
     * <p>Es lo que permite que una aplicación descubra el servidor de autorización partiendo
     * únicamente de la URL base de FHIR. El documento {@code .well-known/smart-configuration} es hoy
     * la vía preferente y esto está obsoleto para las <em>capabilities</em>, pero
     * {@code rest.security} sigue siendo el sitio donde un cliente FHIR genérico —que no sabe de
     * SMART— averigua que aquí hace falta OAuth2.
     *
     * <p>Si no hay autorización configurada no se declara nada. Un {@code CapabilityStatement} que
     * anuncia SMART sobre un servidor abierto es peor que uno mudo.
     */
    private void declararLaSeguridad(CapabilityStatementRestComponent rest) {
        dondeSeAutoriza.direcciones().ifPresent(direcciones -> {
            CapabilityStatementRestSecurityComponent seguridad = rest.getSecurity();
            seguridad.setCors(false);
            seguridad
                    .addService()
                    .addCoding()
                    .setSystem(SERVICIO_DE_SEGURIDAD)
                    .setCode("SMART-on-FHIR")
                    .setDisplay("SMART on FHIR");

            Extension uris = seguridad.addExtension().setUrl(OAUTH_URIS);
            uris.addExtension("authorize", new UriType(direcciones.autorizacion()));
            uris.addExtension("token", new UriType(direcciones.testigo()));
        });
    }

    /**
     * Retira {@code transaction} de las interacciones declaradas.
     *
     * <p>HAPI la declara porque su procesador de transacciones existe, y es verdad que un
     * <em>bundle</em> de solo lecturas funciona. Pero un cliente que lee {@code transaction} en el
     * {@code CapabilityStatement} entiende <strong>«puedo escribir varios recursos y o entran todos o
     * no entra ninguno»</strong>, y eso aquí no pasa: el interceptor de {@code ADR-0014} rechaza con
     * un 422 cualquier transacción que escriba recursos del laboratorio, porque el procesador de
     * transacciones de HAPI no recorre el núcleo ni comprueba sus invariantes.
     *
     * <p>Declarar la mitad que funciona sería peor que no declarar nada: el cliente descubriría el
     * límite al fallar, en producción y a medio camino. La consecuencia está asumida y escrita —es
     * D22— y quien necesite atomicidad la consigue reprocesando, no metiéndolo todo en un sobre.
     */
    private static void noPrometerTransacciones(CapabilityStatementRestComponent rest) {
        rest.getInteraction()
                .removeIf(interaccion -> SystemRestfulInteraction.TRANSACTION.equals(interaccion.getCode()));
    }

    private static void declararSoloLosPerfilesDeLaGuia(CapabilityStatementRestResourceComponent recurso) {
        recurso.getSupportedProfile().clear();
        PerfilesDeLaGuia.deTipo(recurso.getType()).forEach(perfil -> recurso.addSupportedProfile(perfil.canonica()));
    }
}
