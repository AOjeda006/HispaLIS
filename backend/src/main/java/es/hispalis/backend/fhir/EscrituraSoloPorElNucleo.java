package es.hispalis.backend.fhir;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.util.BundleUtil;
import es.hispalis.backend.dominio.ReglaDeNegocioIncumplida;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

/**
 * Cierra la segunda puerta de escritura: el {@code Bundle} de tipo {@code transaction}.
 *
 * <p>El invariante 3 del proyecto dice que <strong>todo lo que entra pasa por el mismo camino</strong>
 * — la API FHIR y, cuando el recurso tiene agregado, el núcleo de dominio—. Los
 * {@code ResourceProvider} propios lo garantizan para {@code POST /fhir/[tipo]}, pero no para una
 * transacción: el procesador de HAPI escribe <strong>llamando a las DAO directamente</strong> y no
 * pasa por ellos. Un {@code ServiceRequest} metido en una transacción quedaría publicado en la
 * proyección sin agregado, sin invariantes y sin fila en el esquema {@code dominio}.
 *
 * <p>Y no se notaría: el recurso se lee luego por la API como cualquier otro. Se notaría el día que
 * alguien cuente las peticiones del dominio y no cuadren con las publicadas — que es exactamente el
 * fallo que el ítem 10 destapó en el {@code PUT}, por la misma causa.
 *
 * <p>Lo que <strong>sí</strong> pasa es una transacción de datos maestros ({@code Organization},
 * {@code Practitioner}): no tienen agregado (§10) y entran por el proveedor estándar de HAPI, así que
 * la transacción no se salta nada. Cerrar la puerta entera sería prohibir una interacción legítima de
 * FHIR por si acaso.
 *
 * <p>Los recursos protegidos <strong>se deducen de los proveedores propios registrados</strong>, no
 * de una lista escrita aquí: dar de alta un proveedor nuevo lo protege solo, sin que nadie tenga que
 * acordarse de tocar esto también.
 */
@Interceptor
@Component
public class EscrituraSoloPorElNucleo {

    private final Set<String> conAgregado;

    public EscrituraSoloPorElNucleo(List<ProveedorPropio> proveedoresPropios) {
        this.conAgregado = proveedoresPropios.stream()
                .map(proveedor -> proveedor.getResourceType().getSimpleName())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @param transaccion el {@code Bundle} recibido, tal y como llegó
     * @throws ReglaDeNegocioIncumplida si contiene algún recurso cuya escritura pasa por el núcleo
     */
    @Hook(Pointcut.STORAGE_TRANSACTION_PROCESSING)
    public void comprobar(IBaseBundle transaccion, RequestDetails peticion) {
        String alSaltarse = BundleUtil.toListOfResources(peticion.getFhirContext(), transaccion).stream()
                .map(IBaseResource::fhirType)
                .filter(conAgregado::contains)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));

        if (!alSaltarse.isEmpty()) {
            throw new ReglaDeNegocioIncumplida(
                    ("Una transacción no puede escribir %s: esos recursos pasan por el núcleo del laboratorio, "
                                    + "que comprueba sus invariantes, y el procesador de transacciones no lo "
                                    + "recorre. Envíalos uno a uno a su propio endpoint.")
                            .formatted(alSaltarse));
        }
    }
}
