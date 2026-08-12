package es.hispalis.backend.infraestructura.exportacion;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.hispalis.backend.aplicacion.exportacion.BarrerExportaciones;
import es.hispalis.backend.aplicacion.exportacion.CerrarExportacion;
import es.hispalis.backend.aplicacion.exportacion.EjecutarExportacion;
import es.hispalis.backend.aplicacion.exportacion.LanzarExportacion;
import es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros;
import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
import es.hispalis.backend.fhir.exportacion.LoQueSaleDeLaCohorte;
import es.hispalis.backend.fhir.exportacion.ProveedorDeExportacion;
import es.hispalis.backend.fhir.seguridad.QuienLlama;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * La exportación masiva, detrás de {@code hispalis.exportacion.habilitada}.
 *
 * <p>Encendida por defecto, y no es incoherente con lo delicada que es la operación: lo que de verdad
 * la cierra son los <em>scopes</em>. Publicar {@code $export} sin que nadie tenga
 * {@code system/Group.rs} + {@code system/*.rs} no expone nada, y tener el interruptor apagado
 * «por si acaso» esconde una operación que después no se prueba.
 *
 * <p>Los casos de uso van como {@code @Bean} y no como {@code @Service}, igual que en el EDO: así el
 * interruptor los quita del contexto de verdad, en vez de dejarlos construidos y sin llamar.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PropiedadesDeExportacion.class)
@ConditionalOnProperty(
        prefix = "hispalis.exportacion",
        name = "habilitada",
        havingValue = "true",
        matchIfMissing = true)
class ConfiguracionExportacion {

    @Bean
    AlmacenDeFicheros almacenDeFicheros(PropiedadesDeExportacion propiedades) {
        return new AlmacenDeFicherosEnDisco(Path.of(propiedades.directorio()));
    }

    /**
     * Un hilo, y uno solo.
     *
     * <p>La IG lo dice sin rodeos: la exportación es intensiva y hay que protegerse de la denegación de
     * servicio, propia y ajena. Con un pozo de hilos, diez peticiones simultáneas de una cohorte grande
     * dejan al laboratorio sin poder registrar un resultado — y el laboratorio es lo que no se puede
     * parar. Encolar es más lento para quien exporta y no le quita nada: ya estaba esperando.
     */
    @Bean
    Executor hiloDeExportacion() {
        return Executors.newSingleThreadExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "exportacion");
            hilo.setDaemon(true);
            return hilo;
        });
    }

    @Bean
    EjecutarExportacion ejecutarExportacion(
            DaoRegistry daos,
            RepositorioDeExportaciones trabajos,
            AlmacenDeFicheros almacen,
            LoQueSaleDeLaCohorte loQueSale,
            FhirContext contexto,
            PropiedadesDeExportacion propiedades) {
        return new EjecutarExportacion(
                daos, trabajos, almacen, loQueSale, contexto, propiedades.caducidad(), propiedades.maximoDeMiembros());
    }

    @Bean
    LanzarExportacion lanzarExportacion(
            RepositorioDeExportaciones trabajos, EjecutarExportacion ejecutar, Executor hiloDeExportacion) {
        return new LanzarExportacion(trabajos, ejecutar, hiloDeExportacion);
    }

    @Bean
    CerrarExportacion cerrarExportacion(RepositorioDeExportaciones trabajos, AlmacenDeFicheros almacen) {
        return new CerrarExportacion(trabajos, almacen);
    }

    @Bean
    BarrerExportaciones barrerExportaciones(
            RepositorioDeExportaciones trabajos, AlmacenDeFicheros almacen, CerrarExportacion cerrar) {
        return new BarrerExportaciones(trabajos, almacen, cerrar);
    }

    @Bean
    BarrenderoDeExportaciones barrenderoDeExportaciones(BarrerExportaciones barrer) {
        return new BarrenderoDeExportaciones(barrer);
    }

    @Bean
    ProveedorDeExportacion proveedorDeExportacion(
            LanzarExportacion lanzar,
            CerrarExportacion cerrar,
            RepositorioDeExportaciones trabajos,
            AlmacenDeFicheros almacen,
            DaoRegistry daos,
            QuienLlama quienLlama,
            ObjectMapper json) {
        return new ProveedorDeExportacion(lanzar, cerrar, trabajos, almacen, daos, quienLlama, json);
    }

    /**
     * Publica las tres operaciones en el servidor FHIR.
     *
     * <p>Va como {@link SmartInitializingSingleton} y no como parámetro de la fábrica del servidor, por
     * la misma razón que la seguridad y la traza: el borde FHIR se construye igual exista o no este
     * {@code @Configuration}, y así el interruptor {@code hispalis.exportacion.habilitada} de verdad
     * quita la operación en vez de dejarla publicada y sin nadie detrás.
     *
     * <p>Como <strong>proveedor suelto</strong>: es como HAPI publica una operación que no sustituye al
     * proveedor de ningún recurso. Quien cierra la escritura de {@code Group} es
     * {@code ProveedorDeCohorte}, que sí es un {@code ProveedorPropio} y existe siempre — una cohorte
     * no se escribe desde fuera aunque la exportación esté apagada.
     */
    @Bean
    SmartInitializingSingleton publicarLaExportacionEnElServidorFhir(
            RestfulServer servidor, ProveedorDeExportacion proveedor) {
        return () -> servidor.registerProvider(proveedor);
    }
}
