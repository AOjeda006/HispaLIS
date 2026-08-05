package es.hispalis.backend.fhir;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.server.IResourceProvider;

/**
 * Marca los proveedores de recurso que escribe este proyecto, frente a los que fabrica HAPI.
 *
 * <p>Existe por tres razones, y ninguna es decorativa:
 *
 * <ol>
 *   <li>El servidor tiene que <strong>sustituir</strong> el proveedor de HAPI por el nuestro cuando
 *       hay uno —registrar dos para el mismo recurso es un error de arranque—, y esta interfaz es
 *       cómo los reconoce sin enumerarlos a mano.
 *   <li>Pedirle a Spring un {@code List<IResourceProvider>} <strong>no vale</strong>: le obliga a
 *       instanciar todos los beans de ese tipo, incluidos los internos de HAPI que solo existen si
 *       se activan funciones fuera de alcance, y el contexto no arranca. Acotando la inyección a
 *       este tipo, solo se materializan los nuestros.
 *   <li>Un proveedor propio es un bean de Spring, así que <strong>la fábrica de HAPI no le inyecta
 *       su DAO</strong>. Hay que enlazarlo a mano o todo lo heredado —leer, buscar,
 *       {@code _history}— falla con un {@code NullPointerException}.
 * </ol>
 */
public interface ProveedorPropio extends IResourceProvider {

    /**
     * Enlaza este proveedor con la DAO de su recurso.
     *
     * <p>Lo llama el servidor al registrarlo, no un {@code @PostConstruct}: en ese momento el
     * registro de DAO ya está completo.
     *
     * @param daos el registro de DAO de HAPI
     */
    void enlazarConSuDao(DaoRegistry daos);
}
