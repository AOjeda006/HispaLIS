package es.hispalis.backend.fhir;

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.config.ThreadPoolFactoryConfig;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.config.HapiJpaConfig;
import ca.uhn.fhir.jpa.config.r5.JpaR5Config;
import ca.uhn.fhir.jpa.config.util.HapiEntityManagerFactoryUtil;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.config.SubscriptionSettings;
import ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect;
import ca.uhn.fhir.jpa.provider.JpaSystemProvider;
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.ApacheProxyAddressStrategy;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ResourceProviderFactory;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import es.hispalis.backend.fhir.auditoria.LaTrazaNoMantieneVivoLoQueObserva;
import es.hispalis.backend.fhir.notificacion.AnotarLasNotificaciones;
import es.hispalis.backend.fhir.notificacion.ProveedorDeSuscripcion;
import es.hispalis.backend.fhir.notificacion.SuscripcionesQueElLaboratorioAcepta;
import es.hispalis.backend.fhir.reconciliacion.ProveedorDeReconciliacion;
import es.hispalis.backend.fhir.seguridad.DondeSeAutoriza;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.search.mapper.orm.cfg.HibernateOrmMapperSettings;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Cableado del servidor FHIR R5: el <em>composition root</em> del borde.
 *
 * <p>El servidor JPA de HAPI se <strong>empotra en esta aplicación</strong> en vez de desplegarse
 * como el {@code hapi-fhir-jpaserver-starter}, que es una aplicación aparte. La razón es la
 * arquitectura, no la comodidad: la proyección FHIR tiene que escribirse en la
 * <strong>misma transacción</strong> que el dominio (D3, §9 del diseño), y eso exige que las DAO de
 * HAPI y el núcleo compartan el gestor de transacciones de este contexto de Spring.
 */
@Configuration
@Import({
    // El servidor R5: contexto, DAO, proveedores de recurso y el grueso de JpaConfig.
    JpaR5Config.class,
    // Búsqueda de texto, borrado de búsquedas caducadas, paginación contra la base de datos.
    HapiJpaConfig.class,
    // Los tres siguientes no son opcionales aunque su función sí esté fuera del alcance del hito 1:
    // el motor de trabajos por lotes de HAPI (`batch2`) sostiene operaciones internas como el
    // reindexado, y arrastra un ejecutor de hilos y un bus en memoria. Sin ellos el contexto no
    // arranca. `Subscription` y `$export` siguen siendo de los hitos 2 y 3.
    ThreadPoolFactoryConfig.class,
    JpaBatch2Config.class,
    Batch2JobsConfig.class,
    SubscriptionChannelConfig.class
})
public class ConfiguracionServidorFhir {

    /** Ruta bajo la que se publica la API. {@code /fhir/metadata} cuelga de aquí. */
    public static final String RUTA_BASE = "/fhir";

    /**
     * Ajustes del almacenamiento de HAPI, con el <strong>caché de búsquedas apagado</strong>.
     *
     * <p>HAPI reutiliza durante 60 segundos el resultado de una búsqueda ya vista. Aquí eso no vale:
     * el patrón de todo cliente que escribe sin duplicar es <em>busca, no encuentra, crea</em>, y el
     * motor de integración lo usa en cada canal —es lo único que hace idempotente el reproceso de un
     * mensaje—. Con el caché encendido, la búsqueda posterior al alta devuelve el conjunto vacío que
     * se guardó antes de ella, el cliente concluye que el recurso no existe y crea un duplicado.
     *
     * <p>No es una optimización que se pierda: <em>read-your-writes</em> es un invariante de este
     * sistema (§9 del diseño), y no dice «el {@code GET} al {@code Location} funciona», dice que
     * ninguna lectura puede ir por detrás de una escritura ya confirmada. Una búsqueda es una
     * lectura. El detalle y cómo se detectó, en {@code docs/adr/adr-0019-…}.
     *
     * <p>⚠️ Y aquí vivía también {@code setEnforceReferentialIntegrityOnDeleteDisableForPaths(…)} con
     * los caminos de la traza, que <strong>no hacía nada</strong>.
     *
     * <p>El ajuste tiene ese nombre exacto y solo lo consulta una clase en todo HAPI 8.10.1:
     * {@code ca.uhn.fhir.jpa.delete.batch2.DeleteExpungeSqlBuilder}, la del trabajo por lotes
     * {@code $delete-expunge}. Un {@code DELETE} normal pasa por {@code DeleteConflictService}, que
     * ni lo mira. Medido buscando el ajuste en el bytecode de todos sus JAR.
     *
     * <p>Lo que sí gobierna un borrado normal es el punto de enganche
     * {@code STORAGE_PRESTORAGE_DELETE_CONFLICTS}, y de eso se ocupa
     * {@link es.hispalis.backend.fhir.auditoria.LaTrazaNoMantieneVivoLoQueObserva}, con la lista de
     * caminos y el porqué. Lo destapó un fallo intermitente del reconciliador; hay test.
     */
    @Bean
    public JpaStorageSettings ajustesDeAlmacenamiento() {
        JpaStorageSettings ajustes = new JpaStorageSettings();
        ajustes.setReuseCachedSearchResultsForMillis(null);
        return ajustes;
    }

    @Bean
    public PartitionSettings ajustesDeParticionado() {
        // Sin particionado: es un laboratorio, no un servicio multi-inquilino.
        return new PartitionSettings();
    }

    @Bean
    public SubscriptionSettings ajustesDeSuscripcion() {
        // `Subscription` es del hito 3. El bean existe porque HAPI lo exige para arrancar.
        return new SubscriptionSettings();
    }

    /**
     * Fábrica de entidades de HAPI.
     *
     * <p>No vale la que autoconfigura Spring Boot: HAPI necesita registrar sus propias entidades y
     * ajustes de Hibernate, así que la construye {@link HapiEntityManagerFactoryUtil} y esta
     * sustituye a la de Boot.
     *
     * <p><strong>Sobre {@code hbm2ddl.auto = update}:</strong> contradice a propósito la convención
     * de Spring de este proyecto ({@code ddl-auto=validate} y el esquema gobernado por migraciones).
     * El esquema de HAPI <em>no es nuestro</em> —lo define y lo migra HAPI—, y fijarlo a mano sería
     * mantener a mano un centenar de tablas ajenas. La regla sigue valiendo para el esquema del
     * dominio, que sí es nuestro y sí llevará migraciones.
     */
    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            ConfigurableListableBeanFactory fabricaDeBeans,
            DataSource origenDeDatos,
            JpaProperties propiedadesJpa,
            FhirContext contexto,
            JpaStorageSettings ajustes) {
        LocalContainerEntityManagerFactoryBean fabrica =
                HapiEntityManagerFactoryUtil.newEntityManagerFactory(fabricaDeBeans, contexto, ajustes);

        // El dialecto es el de HAPI para PostgreSQL, no el genérico de Hibernate: HAPI ajusta con él
        // tipos y funciones propios de su esquema. Y hay que declararlo, no dejar que se deduzca de
        // la conexión: HAPI lo pide antes de que haya conexión, y sin él el arranque muere con un
        // `NullPointerException: Unable to create instance of class: null` que no dice qué falta.
        propiedadesJpa.getProperties().putIfAbsent(AvailableSettings.DIALECT, HapiFhirPostgresDialect.class.getName());
        propiedadesJpa.getProperties().putIfAbsent(AvailableSettings.HBM2DDL_AUTO, "update");
        propiedadesJpa.getProperties().putIfAbsent(AvailableSettings.FORMAT_SQL, "false");
        propiedadesJpa.getProperties().putIfAbsent(AvailableSettings.SHOW_SQL, "false");

        // Hibernate Search sirve la búsqueda de texto libre (`_content`, `_text`) contra Lucene o
        // Elasticsearch. Ni una cosa ni la otra están en el alcance, y si se deja encendido el
        // arranque falla porque no encuentra motor de indexación.
        propiedadesJpa.getProperties().putIfAbsent(HibernateOrmMapperSettings.ENABLED, "false");

        fabrica.setPersistenceUnitName("HISPALIS_PU");
        fabrica.setJpaPropertyMap(propiedadesJpa.getProperties());
        fabrica.setDataSource(origenDeDatos);
        return fabrica;
    }

    /**
     * Gestor de transacciones compartido por el dominio y la proyección.
     *
     * <p><strong>Fijar el {@code DataSource} no es opcional ni decorativo:</strong> es lo que hace
     * que el SQL del repositorio de dominio tome la conexión que la transacción JPA ya tiene abierta
     * en vez de pedir una suya. Sin esta línea habría dos transacciones, la proyección podría
     * confirmarse con el dominio revertido, y el <em>read-your-writes</em> de §9 sería mentira. Hay
     * un test que lo comprueba dando de alta dos veces el mismo NHC.
     */
    @Bean
    @Primary
    public JpaTransactionManager transactionManager(EntityManagerFactory fabricaDeEntidades, DataSource origenDeDatos) {
        JpaTransactionManager gestor = new JpaTransactionManager();
        gestor.setEntityManagerFactory(fabricaDeEntidades);
        gestor.setDataSource(origenDeDatos);
        return gestor;
    }

    /**
     * El servidor FHIR propiamente dicho.
     *
     * <p>Los proveedores de recurso <strong>no se enumeran</strong>: los fabrica HAPI a partir de
     * los DAO registrados, así que el {@code CapabilityStatement} describe lo que el servidor sabe
     * hacer de verdad y no lo que alguien escribió una vez en una lista. Las excepciones son los
     * recursos cuya escritura pasa por el dominio, que traen proveedor propio.
     */
    @Bean
    public RestfulServer servidorFhir(
            FhirContext contexto,
            IFhirSystemDao<?, ?> systemDao,
            DaoRegistry daos,
            JpaSystemProvider<?, ?> proveedorDeSistema,
            ResourceProviderFactory fabricaDeProveedores,
            JpaStorageSettings ajustes,
            ISearchParamRegistry parametrosDeBusqueda,
            IValidationSupport soporteDeValidacion,
            DatabaseBackedPagingProvider paginacion,
            List<ProveedorPropio> proveedoresPropios,
            ProveedorDeReconciliacion proveedorDeReconciliacion,
            ProveedorDeSuscripcion proveedorDeSuscripcion,
            TraduccionDeErroresDeDominio traduccionDeErrores,
            IInterceptorService interceptoresDeAlmacenamiento,
            EscrituraSoloPorElNucleo escrituraSoloPorElNucleo,
            SoloLosVerbosQueElNucleoGobierna soloLosVerbosQueElNucleoGobierna,
            AnotarLasNotificaciones anotarLasNotificaciones,
            SuscripcionesQueElLaboratorioAcepta suscripcionesAceptadas,
            LaTrazaNoMantieneVivoLoQueObserva laTrazaNoRetiene,
            DondeSeAutoriza dondeSeAutoriza) {
        RestfulServer servidor = new RestfulServer(contexto);

        servidor.registerProviders(sustituyendoLosPropios(fabricaDeProveedores, proveedoresPropios));
        // Los proveedores propios son beans de Spring, así que HAPI no les ha inyectado su DAO: eso
        // lo hace su fábrica, y a los nuestros no los fabrica ella. Sin esto, todo lo que hereden de
        // HAPI —leer, buscar, `_history`— falla con un NullPointerException.
        proveedoresPropios.forEach(proveedor -> proveedor.enlazarConSuDao(daos));
        servidor.registerProviders(proveedoresPropios);
        servidor.registerProvider(proveedorDeSistema);

        // La reconciliación es una operación de sistema, no de un recurso: no cuelga de ningún tipo
        // porque los recorre todos. Va como proveedor suelto, que es como HAPI publica `[base]/$…`.
        servidor.registerProvider(proveedorDeReconciliacion);

        // `$status` y `$events` de `Subscription`, que HAPI 8.10 no trae. Van como proveedor suelto y
        // NO como `ProveedorPropio`: eso sustituiría al proveedor de HAPI para `Subscription` y le
        // aplicaría las puertas de los recursos con agregado detrás, que este no tiene.
        servidor.registerProvider(proveedorDeSuscripcion);

        // Sin esto, un invariante de negocio incumplido saldría como 500.
        servidor.registerInterceptor(traduccionDeErrores);

        // Y este cierra los cinco verbos de escritura que los proveedores propios HEREDAN sin
        // sustituir, más los dos que HAPI 8 añadió después. Va en el registro del `RestfulServer`
        // —y no en el del almacenamiento, como el de las transacciones— porque el punto de enganche
        // es `SERVER_INCOMING_REQUEST_PRE_HANDLED`, que lo dispara el propio servidor REST.
        servidor.registerInterceptor(soloLosVerbosQueElNucleoGobierna);

        // Y este va en el servicio de interceptores del almacenamiento, no en el del servidor: los
        // puntos de enganche `STORAGE_*` los dispara la capa JPA, que no consulta el registro del
        // `RestfulServer`. Registrarlo en el sitio equivocado no da ningún error — simplemente no se
        // llama nunca, que en un interceptor que cierra una puerta es la peor forma de fallar.
        interceptoresDeAlmacenamiento.registerInterceptor(escrituraSoloPorElNucleo);

        // Los dos de las notificaciones van también en el registro del almacenamiento, y por la misma
        // razón: se enganchan en puntos `STORAGE_*`. Anotar lo notificable tiene que ver TODA
        // escritura de la proyección, incluidas las que hacen los casos de uso llamando a las DAO sin
        // que haya una petición REST detrás.
        interceptoresDeAlmacenamiento.registerInterceptor(suscripcionesAceptadas);
        interceptoresDeAlmacenamiento.registerInterceptor(anotarLasNotificaciones);

        // Y el que deja borrar lo que una traza se limitó a mirar. También `STORAGE_*`, y también
        // aquí: el reconciliador borra llamando a las DAO, sin petición REST detrás.
        interceptoresDeAlmacenamiento.registerInterceptor(laTrazaNoRetiene);
        servidor.setServerConformanceProvider(new ConformidadHispaLis(
                servidor, systemDao, ajustes, parametrosDeBusqueda, soporteDeValidacion, dondeSeAutoriza));

        // La paginación va contra la base de datos, no contra memoria: es lo que hace que
        // `Bundle.link[relation=next]` siga funcionando con un resultado grande (ítem 11).
        servidor.setPagingProvider(paginacion);

        // Y el enlace tiene que apuntar a donde el cliente pueda ir. El servidor lo construye a
        // partir de la petición que le llega, así que detrás de un proxy —que es como lo alcanza el
        // navegador, tanto en desarrollo como en el `compose` del ítem 15— saldría con la dirección
        // interna del contenedor. El cliente trata esa URL como opaca, que es lo correcto, y por eso
        // no puede corregirla: si viene mal, la segunda página es inalcanzable. Con esta estrategia
        // se respetan las cabeceras `X-Forwarded-*` cuando las hay, y se cae a la dirección de la
        // petición cuando no.
        servidor.setServerAddressStrategy(ApacheProxyAddressStrategy.forHttp());

        // JSON por defecto. FHIR admite XML y JSON, pero un cliente que no negocia debe recibir lo
        // que espera todo el ecosistema actual.
        servidor.setDefaultResponseEncoding(EncodingEnum.JSON);
        return servidor;
    }

    /**
     * Devuelve los proveedores que fabrica HAPI, menos aquellos cuyo recurso tiene proveedor propio.
     *
     * <p>Registrar dos proveedores para el mismo tipo es un error de arranque, así que el nuestro no
     * se «añade»: sustituye. Se filtra por el tipo de recurso y no por una lista de nombres, para
     * que dar de alta un proveedor propio nuevo no obligue a acordarse de tocar esto también.
     */
    private static List<Object> sustituyendoLosPropios(
            ResourceProviderFactory fabricaDeProveedores, List<ProveedorPropio> proveedoresPropios) {
        Set<Class<? extends IBaseResource>> conProveedorPropio = proveedoresPropios.stream()
                .map(ProveedorPropio::getResourceType)
                .collect(Collectors.toSet());

        return fabricaDeProveedores.createProviders().stream()
                .filter(proveedor -> !(proveedor instanceof IResourceProvider deHapi
                        && conProveedorPropio.contains(deHapi.getResourceType())))
                .toList();
    }

    @Bean
    public ServletRegistrationBean<RestfulServer> registroDelServidorFhir(RestfulServer servidorFhir) {
        ServletRegistrationBean<RestfulServer> registro = new ServletRegistrationBean<>(servidorFhir, RUTA_BASE + "/*");
        registro.setName("fhir");
        registro.setLoadOnStartup(1);
        return registro;
    }
}
