package es.hispalis.backend.aplicacion.exportacion;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import es.hispalis.backend.dominio.exportacion.RepositorioDeExportaciones;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion;
import es.hispalis.backend.dominio.exportacion.TrabajoDeExportacion.Fichero;
import es.hispalis.backend.fhir.exportacion.LoQueSaleDeLaCohorte;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Group;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monta los NDJSON de una cohorte.
 *
 * <p>Corre fuera de la petición que la pidió, que es la mitad de lo que significa «asíncrona». La otra
 * mitad es que el cliente no espera: se le dio un {@code 202} y una URL de sondeo antes de que esto
 * empezara.
 *
 * <p><strong>Se escribe línea a línea y no se acumula en memoria.</strong> Es el anti-patrón que la
 * propia IG de Bulk Data señala por el lado del que ingiere, y vale igual por el lado del que produce:
 * una cohorte de mil personas concatenada en un {@code String} es una exportación que funciona en
 * desarrollo y tumba el servidor el día que hace falta.
 *
 * <p>Lo que sale de aquí <strong>no es lo que hay en la proyección</strong>: pasa antes por
 * {@link LoQueSaleDeLaCohorte}, que es donde vive la decisión de qué se cede.
 */
public class EjecutarExportacion {

    private static final Logger LOG = LoggerFactory.getLogger(EjecutarExportacion.class);

    private final DaoRegistry daos;
    private final RepositorioDeExportaciones trabajos;
    private final es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros almacen;
    private final LoQueSaleDeLaCohorte loQueSale;
    private final FhirContext contexto;
    private final Duration caducidad;
    private final int maximoDeMiembros;

    public EjecutarExportacion(
            DaoRegistry daos,
            RepositorioDeExportaciones trabajos,
            es.hispalis.backend.dominio.exportacion.AlmacenDeFicheros almacen,
            LoQueSaleDeLaCohorte loQueSale,
            FhirContext contexto,
            Duration caducidad,
            int maximoDeMiembros) {
        this.daos = daos;
        this.trabajos = trabajos;
        this.almacen = almacen;
        this.loQueSale = loQueSale;
        this.contexto = contexto;
        this.caducidad = caducidad;
        this.maximoDeMiembros = maximoDeMiembros;
    }

    /**
     * @param trabajoId el trabajo abierto por {@link LanzarExportacion}
     * @param tipos los tipos pedidos con {@code _type}, o vacío para todos los exportables
     */
    @Transactional
    public void ejecutar(UUID trabajoId, List<String> tipos) {
        Optional<TrabajoDeExportacion> encontrado = trabajos.buscar(trabajoId);
        if (encontrado.isEmpty()) {
            LOG.warn("La exportación {} ya no existe cuando le tocaba ejecutarse.", trabajoId);
            return;
        }
        TrabajoDeExportacion trabajo = encontrado.get();

        try {
            trabajo.terminar(escribirLosFicheros(trabajo, tipos), caducidad, Instant.now());
            LOG.info(
                    "Exportación {} de {}: {} ficheros, disponibles {}.",
                    trabajo.id(),
                    trabajo.cohorte(),
                    trabajo.ficheros().size(),
                    caducidad);
        } catch (RuntimeException fallo) {
            // El motivo es técnico y se guarda entero: aquí no hay nada del paciente que contar, y una
            // exportación no falla por lo que ponga en los datos.
            trabajo.fallar(fallo.getClass().getSimpleName() + ": " + fallo.getMessage());
            almacen.borrar(trabajo.id());
            LOG.error("La exportación {} de {} ha fallado.", trabajo.id(), trabajo.cohorte(), fallo);
        }
        trabajos.guardar(trabajo);
    }

    private List<Fichero> escribirLosFicheros(TrabajoDeExportacion trabajo, List<String> tipos) {
        List<String> miembros = miembrosDe(trabajo.cohorte());
        List<Fichero> escritos = new ArrayList<>();

        for (String tipo : tipos.isEmpty() ? LoQueSaleDeLaCohorte.TIPOS_EXPORTABLES : tipos) {
            String nombre = tipo + ".ndjson";
            long lineas = almacen.escribir(
                    trabajo.id(), nombre, deTipo(tipo, miembros).stream().map(recurso -> contexto.newJsonParser()
                            .encodeResourceToString(recurso)));
            if (lineas > 0) {
                // El billete es opaco y de un solo significado: «este fichero de esta exportación».
                // Nada de la cohorte ni del paciente viaja en la URL de descarga (adr-0016).
                escritos.add(new Fichero(UUID.randomUUID().toString(), tipo, nombre, lineas));
            }
        }
        return escritos;
    }

    /**
     * Los pacientes de la cohorte, con tope.
     *
     * <p>El tope no es prudencia abstracta: la exportación es intensiva y comparte base de datos con la
     * operación asistencial. Una cohorte que crece sola —y las de vigilancia crecen solas— acaba siendo
     * la consulta que deja al laboratorio sin poder registrar un resultado.
     */
    private List<String> miembrosDe(String cohorte) {
        Group grupo = daos.getResourceDao(Group.class).read(new IdType(cohorte), new SystemRequestDetails());
        return grupo.getMember().stream()
                .filter(miembro -> !miembro.getInactive())
                .map(miembro -> miembro.getEntity().getReference())
                .filter(referencia -> referencia != null && referencia.startsWith("Patient/"))
                .limit(maximoDeMiembros)
                .toList();
    }

    /**
     * Los recursos de un tipo para toda la cohorte, ya pasados por el filtro de lo que se cede.
     *
     * <p>{@code Patient} se lee uno a uno porque los miembros ya vienen identificados; el resto se
     * busca por sujeto, que es lo que el compartimento del paciente significa aquí.
     */
    private List<Resource> deTipo(String tipo, List<String> miembros) {
        if (!LoQueSaleDeLaCohorte.TIPOS_EXPORTABLES.contains(tipo)) {
            return List.of();
        }
        Set<Resource> recogidos = new LinkedHashSet<>();

        for (String miembro : miembros) {
            if ("Patient".equals(tipo)) {
                anadir(recogidos, daos.getResourceDao("Patient").read(new IdType(miembro), new SystemRequestDetails()));
                continue;
            }
            SearchParameterMap porSujeto =
                    SearchParameterMap.newSynchronous().add("subject", new ReferenceParam(miembro));
            IBundleProvider encontrados = daos.getResourceDao(tipo).search(porSujeto, new SystemRequestDetails());
            encontrados.getAllResources().forEach(recurso -> anadir(recogidos, recurso));
        }
        return List.copyOf(recogidos);
    }

    private void anadir(Set<Resource> recogidos, IBaseResource recurso) {
        loQueSale.comoSale((Resource) recurso).ifPresent(recogidos::add);
    }
}
