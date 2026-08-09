package es.hispalis.backend.fhir;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationConstants;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Cierra las puertas de escritura que los proveedores propios <strong>heredan sin sustituir</strong>.
 *
 * <p>{@code BaseJpaResourceProvider} expone siete verbos que escriben. Los proveedores de este
 * proyecto sustituyen {@code create} y {@code update}, que son los que traducen a comandos de
 * dominio. Los otros cinco —{@code patch}, {@code delete}, {@code metaAdd}, {@code metaDelete} y
 * {@code expunge}— más los dos que HAPI 8 añadió después —{@code $merge} y {@code $undo-merge}—
 * seguían siendo los de HAPI: <strong>escriben la proyección y dejan el dominio atrás</strong>.
 *
 * <p><strong>Medido</strong> contra HAPI 8.10.1 antes de cerrar nada, porque el dato cambia el orden
 * del trabajo:
 *
 * <table>
 *   <caption>Alcanzables de verdad con el {@code JpaStorageSettings} de este proyecto</caption>
 *   <tr><th>Verbo</th><th>Antes</th><th>Qué pasaba</th></tr>
 *   <tr><td>{@code PATCH}</td><td>{@code 200}</td><td>cambiaba el sexo del paciente y subía a {@code versionId 2}</td></tr>
 *   <tr><td>{@code DELETE}</td><td>{@code 200}</td><td><em>«Successfully deleted 1 resource(s)»</em>, con el agregado intacto</td></tr>
 *   <tr><td>{@code $meta-add}</td><td>{@code 200}</td><td>etiquetaba el recurso publicado</td></tr>
 *   <tr><td>{@code $meta-delete}</td><td>{@code 200}</td><td>le quitaba la etiqueta</td></tr>
 *   <tr><td>{@code $expunge}</td><td>rechazado</td><td>por HAPI: {@code expungeEnabled} viene apagado</td></tr>
 *   <tr><td>{@code $merge}</td><td>rechazado</td><td>por HAPI: le falta el servicio de fusión</td></tr>
 *   <tr><td>{@code $undo-merge}</td><td>rechazado</td><td>ídem</td></tr>
 * </table>
 *
 * <p>Las tres últimas se cierran igual, y esa es la parte que importa: <strong>una puerta que está
 * cerrada por un valor por defecto no está cerrada</strong>. {@code expungeEnabled} es un ajuste que
 * alguien enciende un día para limpiar una base de pruebas, y el <em>bean</em> de fusión aparece
 * solo con actualizar la librería. Ninguna de las dos cosas haría fallar nada que avisara de que
 * esto se ha abierto.
 *
 * <p><strong>Por qué un interceptor y no siete métodos en cada proveedor.</strong> Es la regla 3 de
 * {@code ADR-0014} —las puertas laterales se cierran en la capa que las dispara— y evita cuarenta y
 * dos sobreescrituras repartidas por seis clases, que es un sitio estupendo donde olvidarse de una.
 * Y como la regla 4, <strong>la lista de recursos protegidos se deduce</strong> de los proveedores
 * registrados: dar de alta un proveedor propio nuevo lo protege solo.
 *
 * <p>Los verbos, en cambio, <strong>sí</strong> están enumerados, porque no hay de dónde deducirlos:
 * los pone el framework. Que esa enumeración se quede corta al actualizar HAPI es un riesgo real
 * —{@code $merge} apareció así— y lo vigila {@code PuertasHeredadasTest}, que compara por reflexión
 * los métodos de escritura de {@code BaseJpaResourceProvider} con los que aquí se conocen.
 */
// El orden importa y no es un detalle: `AuthorizationInterceptor` se engancha en ESTE MISMO punto,
// con orden 200, y quien conteste primero decide qué error ve el cliente. Va detrás a propósito —
// «no tienes permiso» tiene que ganar a «este verbo no se admite aquí», porque contestar lo segundo
// a quien no está autorizado le cuenta lo que el servidor sabe hacer. Con el orden por defecto
// contestaba `422` donde el ítem 35 exige `403`, y lo pilló su test.
@Interceptor(order = AuthorizationConstants.ORDER_AUTH_INTERCEPTOR + 10)
@Component
public class SoloLosVerbosQueElNucleoGobierna {

    /**
     * Los verbos REST heredados que no se admiten, con lo que hay que hacer en su lugar.
     *
     * <p>{@code CREATE} y {@code UPDATE} no están: esos sí los gobierna el núcleo.
     */
    private static final Map<RestOperationTypeEnum, String[]> VERBOS_CERRADOS = Map.of(
            RestOperationTypeEnum.PATCH,
                    new String[] {
                        "PATCH",
                        "una modificación parcial no puede comprobar los invariantes del agregado, porque no se "
                                + "sabe con qué queda el recurso hasta después de aplicarla",
                        "envía el recurso entero con `PUT`, que sí pasa por el núcleo"
                    },
            RestOperationTypeEnum.DELETE,
                    new String[] {
                        "DELETE",
                        "una historia clínica no se borra, y borrar la proyección dejaría el agregado vivo y sin "
                                + "recurso publicado",
                        "si el dato no procede, cámbiale el estado: un espécimen se rechaza "
                                + "(`unsatisfactory`) y una línea de petición se anula (`revoked`)"
                    });

    /**
     * Las operaciones {@code $…} heredadas que no se admiten.
     *
     * <p>Van aparte porque llegan como {@code EXTENDED_OPERATION_*} y hay que mirar el nombre. Es
     * una lista corta y explícita a propósito: {@code $validar} y {@code $reconciliar} son
     * <em>nuestras</em> y tienen que pasar, así que rechazar toda operación desconocida cerraría el
     * mecanismo por el que este proyecto añade las suyas.
     */
    private static final Map<String, String[]> OPERACIONES_CERRADAS = Map.of(
            "$meta-add",
                    new String[] {
                        "$meta-add",
                        "las etiquetas de `meta` viajan con el recurso publicado y aquí no significan nada: el "
                                + "estado del laboratorio vive en el dominio, no en una etiqueta",
                        "usa el elemento del recurso que corresponda"
                    },
            "$meta-delete",
                    new String[] {
                        "$meta-delete",
                        "por lo mismo que `$meta-add`: quitar una etiqueta que el laboratorio no pone no tiene "
                                + "sentido, y sí modifica el recurso publicado",
                        "usa el elemento del recurso que corresponda"
                    },
            "$expunge",
                    new String[] {
                        "$expunge",
                        "borra el recurso y su historial sin dejar rastro, que es lo contrario de lo que un "
                                + "laboratorio tiene que poder demostrar",
                        "no hay sustituto: si hay que rehacer la proyección, `POST [base]/$reconciliar` la "
                                + "regenera desde el dominio"
                    },
            "$hapi.fhir.merge",
                    new String[] {
                        "$hapi.fhir.merge",
                        "fusionar dos pacientes reescribe las referencias de todo lo que colgaba del origen y "
                                + "puede borrarlo, sin que el dominio se entere de que dos historias eran una",
                        "la unificación de historias es un caso de uso del laboratorio y todavía no está modelada"
                    },
            "$hapi.fhir.undo-merge",
                    new String[] {
                        "$hapi.fhir.undo-merge",
                        "deshace una fusión que aquí no se puede hacer",
                        "no hay nada que deshacer"
                    });

    private final Set<String> gobernados;

    public SoloLosVerbosQueElNucleoGobierna(List<ProveedorPropio> proveedoresPropios) {
        this.gobernados = proveedoresPropios.stream()
                .map(proveedor -> proveedor.getResourceType().getSimpleName())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Rechaza el verbo antes de que llegue al proveedor.
     *
     * <p>Se engancha en {@code SERVER_INCOMING_REQUEST_PRE_HANDLED} y no antes: es el primer punto
     * en el que HAPI ya ha resuelto a qué método iba la petición, así que aquí
     * {@code getRestOperationType()} y {@code getOperation()} dicen la verdad.
     *
     * @param peticion la petición, con el tipo de recurso y el verbo ya resueltos
     * @param verbo qué operación REST se ha resuelto
     * @throws PuertaHeredadaCerrada si el verbo escribe y el recurso lo gobierna el núcleo
     */
    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    public void comprobar(RequestDetails peticion, RestOperationTypeEnum verbo) {
        // El nombre del recurso es NULO en todo lo que no cuelga de un tipo: `metadata`, la
        // paginación, la transacción de la raíz. Y `Set.of(…).contains(null)` no devuelve `false`:
        // lanza `NullPointerException`, que aquí sale como un `500` en `GET /fhir/metadata`.
        String recurso = peticion.getResourceName();
        if (recurso == null || !gobernados.contains(recurso)) {
            return;
        }

        String[] motivo = VERBOS_CERRADOS.get(verbo);
        if (motivo == null && esOperacion(verbo)) {
            motivo = OPERACIONES_CERRADAS.get(peticion.getOperation());
        }
        if (motivo != null) {
            throw new PuertaHeredadaCerrada(motivo[0], motivo[1], motivo[2]);
        }
    }

    private static boolean esOperacion(RestOperationTypeEnum verbo) {
        return verbo == RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE
                || verbo == RestOperationTypeEnum.EXTENDED_OPERATION_TYPE;
    }
}
