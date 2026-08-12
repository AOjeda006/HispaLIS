package es.hispalis.backend.fhir.seguridad;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilder;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOp;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRuleBuilderRuleOpClassifier;
import ca.uhn.fhir.rest.server.interceptor.auth.PolicyEnum;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import es.hispalis.backend.fhir.seguridad.AmbitoSmart.Permiso;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r5.model.Group;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Provenance;
import org.hl7.fhir.r5.model.Subscription;

/**
 * Lo que los <em>scopes</em> del testigo permiten hacer: verbos por tipo de recurso.
 *
 * <p>Es <strong>la mitad</strong> del control de acceso, y conviene tener clara cuál. Aquí se
 * responde a «¿puede este cliente leer {@code Observation}?». A «¿puede ver <em>esta</em>
 * {@code Observation}?» responde {@link ConsentimientoDelPaciente}, y responde después. Un
 * <em>scope</em> concedido no garantiza los datos: eso no es un matiz de la norma, es el orden en el
 * que están escritos estos dos ficheros.
 *
 * <p><strong>Por qué el compartimento del paciente NO está aquí.</strong> HAPI sabe hacerlo
 * ({@code inCompartment}), y ponerlo también en este fichero parecería defensa en profundidad. Sería
 * lo contrario: la regla quedaría escrita en dos sitios que hay que cambiar a la vez, y el día que
 * discreparan la que mandaría sería la que nadie estaba leyendo. Vive en un único sitio, con nombre
 * propio, y hay un test que falla si el interceptor de consentimiento no está registrado.
 *
 * <p><strong>Sobre buscar y leer.</strong> HAPI evalúa una búsqueda como una lectura de cada recurso
 * devuelto, así que {@code r} y {@code s} acaban en la misma regla. No se disimula: la norma
 * recomienda factorizar a {@code .rs} justamente porque separarlos aporta poco, y un
 * {@code user/Observation.s} suelto concedería aquí lectura directa. El día que haga falta
 * distinguirlos, el sitio es este y no otro.
 */
public class AutorizacionSmart extends AuthorizationInterceptor {

    private final QuienLlama quienLlama;

    public AutorizacionSmart(QuienLlama quienLlama) {
        // Denegar por defecto. Es lo único aceptable: con `ALLOW` por defecto, un scope que este
        // servidor no supiera interpretar se convertiría en permiso total.
        super(PolicyEnum.DENY);
        this.quienLlama = quienLlama;
    }

    @Override
    public List<IAuthRule> buildRuleList(RequestDetails peticion) {
        IAuthRuleBuilder reglas = new RuleBuilder();

        // Lo que hace el SERVIDOR por su cuenta no se juzga con los scopes de nadie, porque no hay
        // nadie: un `SystemRequestDetails` no puede llegar por el cable, es la marca con la que HAPI
        // dice «esto lo estoy haciendo yo». Es lo que usan el notificador EDO al abrir una
        // declaración, el reconciliador al reparar y la traza de acceso al levantar acta.
        //
        // Sin esta regla, esas tres escrituras se evalúan contra el testigo del hilo en curso —o
        // contra ninguno, en un hilo de fondo— y se deniegan. Y falla en el peor sentido posible: la
        // traza del acceso de un testigo de solo lectura sería justo la que no se escribe, así que el
        // registro quedaría lleno de los accesos inocuos y vacío de los que interesa mirar.
        if (peticion instanceof SystemRequestDetails) {
            return reglas.allowAll("operación interna del servidor: no hay cliente al que aplicar scopes")
                    .build();
        }

        // El `CapabilityStatement` es público: es como un cliente descubre el contrato y dónde está
        // el servidor de autorización. Pedir testigo para leerlo sería pedir que se adivine.
        reglas.allow("descubrimiento").metadata();

        Optional<Testigo> testigo = quienLlama.testigo();
        if (testigo.isEmpty()) {
            return reglas.denyAll("Esta API exige un testigo de acceso SMART on FHIR.")
                    .build();
        }

        for (AmbitoSmart ambito : testigo.get().ambitos()) {
            aplicar(reglas, ambito);
        }
        if (puedeExportar(testigo.get())) {
            permitirLaExportacion(reglas);
        }

        return reglas.denyAll("Los scopes de este testigo no alcanzan a lo que se ha pedido.")
                .build();
    }

    /**
     * Quién puede exportar una cohorte entera.
     *
     * <p>Es la única regla del fichero que mira el testigo <strong>completo</strong> y no scope a
     * scope, porque lo que exige es una <em>combinación</em>: {@code system/Group.rs} y
     * {@code system/*.rs}, las dos, desde un cliente de sistema. La IG de Bulk Data lo dice así —hacen
     * falta dos cosas, autorización sobre los recursos y sobre el propio {@code Group}— y aquí tiene el
     * mismo efecto que la regla de {@code $reconciliar}: <strong>ningún cliente del <em>realm</em> lo
     * tiene concedido de fábrica</strong>, así que dárselo a alguien es un acto explícito de quien
     * administra la identidad.
     *
     * <p>Un testigo de <strong>usuario</strong> no exporta ni con {@code user/*.cruds}, que es más de
     * lo que tiene ningún facultativo. No es rigidez: una exportación masiva no es un acto asistencial
     * —nadie atiende a doscientas personas a la vez— y el consentimiento recurso a recurso del ítem 35
     * no se le puede aplicar.
     *
     * <p><strong>El permiso sobre {@code Group} tiene que pedirse por su nombre.</strong> Un
     * {@code system/*.rs} a secas <em>incluye</em> {@code Group} y aun así no basta, y es a propósito:
     * si el comodín valiera, la mitad «autorización sobre el grupo» de la regla no existiría en la
     * práctica y cualquier cliente de lectura total exportaría sin que nadie lo hubiera decidido.
     * Escribirlo obliga a que quien emite el testigo sepa que habrá exportaciones.
     */
    private static boolean puedeExportar(Testigo testigo) {
        List<AmbitoSmart> deSistema = testigo.ambitosDe(AmbitoSmart.Contexto.SISTEMA);

        boolean sobreLaCohorte = deSistema.stream()
                .anyMatch(ambito -> "Group".equals(ambito.tipoDeRecurso()) && ambito.alcanza("Group", Permiso.LEER));
        boolean sobreTodoLoQueSeLleva = deSistema.stream()
                .anyMatch(ambito -> ambito.todosLosTipos() && ambito.permisos().contains(Permiso.LEER));

        return sobreLaCohorte && sobreTodoLoQueSeLleva;
    }

    /**
     * Las tres puertas de Bulk Data, autorizadas juntas.
     *
     * <p>El sondeo y la descarga van {@code onServer()} porque no cuelgan de ningún recurso — el
     * trabajo no es un recurso FHIR—. Que estén aquí y no sueltas significa que <strong>quien no puede
     * exportar tampoco puede sondear ni descargar</strong>, que es lo que hay que exigir: el
     * identificador de un trabajo viaja en una cabecera y en el log de un proxy, y sin esto valdría
     * como llave.
     */
    private static void permitirLaExportacion(IAuthRuleBuilder reglas) {
        reglas.allow("exportar una cohorte")
                .operation()
                .named("$export")
                .onInstancesOfType(Group.class)
                .andAllowAllResponses();
        reglas.allow("sondear la exportación")
                .operation()
                .named("$export-estado")
                .onServer()
                .andAllowAllResponses();
        reglas.allow("descargar la exportación")
                .operation()
                .named("$export-fichero")
                .onServer()
                .andAllowAllResponses();
    }

    private static void aplicar(IAuthRuleBuilder reglas, AmbitoSmart ambito) {
        String nombre = "scope " + ambito.contexto() + " " + ambito.tipoDeRecurso();

        if (ambito.permisos().contains(Permiso.LEER) || ambito.permisos().contains(Permiso.BUSCAR)) {
            sobreElTipo(reglas.allow(nombre + " leer").read(), ambito).withAnyId();
        }
        if (ambito.permisos().contains(Permiso.CREAR)) {
            sobreElTipo(reglas.allow(nombre + " crear").create(), ambito).withAnyId();
        }
        if (ambito.permisos().contains(Permiso.ACTUALIZAR)) {
            sobreElTipo(reglas.allow(nombre + " actualizar").write(), ambito).withAnyId();
        }
        if (ambito.permisos().contains(Permiso.BORRAR)) {
            sobreElTipo(reglas.allow(nombre + " borrar").delete(), ambito).withAnyId();
        }

        // `$validar` cambia el estado de un resultado, así que se autoriza con el permiso de
        // actualizar sobre `Observation`. SMART no tiene scopes de operación: una operación se
        // autoriza por lo que le hace a los recursos, y lo que esta hace es firmar uno.
        //
        // ⚠️ **Autorizar la operación no autoriza lo que la operación escribe.** `$validar` firma el
        // resultado y escribe **también un `Provenance`** —la constancia de quién firmó— en la misma
        // transacción, y el interceptor comprueba cada recurso que se almacena, en
        // `STORAGE_PRESTORAGE_RESOURCE_CREATED` y en `…_UPDATED`. Sin la segunda regla, un testigo
        // con `user/Observation.u` pasaba la autorización de la operación y se llevaba un `403` al
        // escribir la procedencia, con un mensaje que no dice qué recurso lo provocó.
        // `andAllowAllResponsesWithAllResourcesAccess()` **tampoco basta**: eso abre la respuesta,
        // no la escritura. Medido contra HAPI 8.10.1.
        //
        // **La operación llevaba desde el ítem 18 sin poder ejecutarse con la seguridad puesta** y
        // no lo veía nadie: los tests de integración la apagan y ningún cliente llama todavía a
        // `$validar` —la web no tiene pantalla de validación—. Apareció al recorrer el circuito v2
        // contra el `compose`.
        //
        // ⚠️ Y **`write()`, no `create()`**, por la misma trampa una segunda vez. La primera firma da
        // de alta una procedencia; la segunda hace que `ValidarResultado` **reescriba las que ya
        // había**, así que llega al interceptor como una modificación. Con la regla en `create()`,
        // un crítico dejaba poner la primera firma y **no la segunda**: se quedaba en `preliminary`
        // para siempre y fuera de todo informe. Lo destapó el circuito del ítem 51 contra el
        // `compose`; los tests no, porque el que enciende la seguridad corría con un catálogo sin
        // umbrales críticos y allí una firma basta. Está en `adr-0033` y lo prueba
        // `DobleValidacionConSeguridadTest`.
        //
        // Conceder la escritura de `Provenance` no abre ninguna puerta: lo que se escribe es efecto
        // de un acto ya autorizado, y desde fuera no se puede escribir uno —`ProveedorDeProcedencia`
        // rechaza `POST` y `PUT`, el interceptor de transacciones lo protege y los verbos heredados
        // están cerrados—. Hay un test que lo comprueba con este mismo testigo.
        if (ambito.permisos().contains(Permiso.ACTUALIZAR) && ambito.alcanza("Observation", Permiso.ACTUALIZAR)) {
            reglas.allow(nombre + " validar")
                    .operation()
                    .named("$validar")
                    .onInstancesOfType(Observation.class)
                    .andAllowAllResponses();
            reglas.allow(nombre + " procedencia de la validación")
                    .write()
                    .resourcesOfType(Provenance.class)
                    .withAnyId();
        }

        // `$status` y `$events` son la forma en que un suscriptor mira lo suyo: en qué estado está su
        // suscripción, por qué falló una entrega y qué números de evento se ha perdido. Las dos son
        // **lecturas** —`idempotent = true`— y por eso se autorizan con el permiso de leer
        // `Subscription`, igual que `$validar` se autoriza con el de actualizar `Observation`.
        //
        // ⚠️ Sin esto, un cliente con `system/Subscription.crs` **creaba su suscripción y no podía
        // preguntar por ella**: `403` en `$status`. Y es justo el cliente que más lo necesita, porque
        // `eventsSinceSubscriptionStart` es lo único que le dice cuánto se perdió mientras estuvo
        // caído. Otra vez lo mismo que en `adr-0033`: **autorizar el recurso no autoriza la
        // operación**, aunque la operación no haga más que leerlo. Apareció en el circuito del
        // ítem 51 contra el `compose`.
        if (ambito.alcanza("Subscription", Permiso.LEER) || ambito.alcanza("Subscription", Permiso.BUSCAR)) {
            reglas.allow(nombre + " estado de la suscripción")
                    .operation()
                    .named("$status")
                    .onInstancesOfType(Subscription.class)
                    .andAllowAllResponses();
            reglas.allow(nombre + " eventos de la suscripción")
                    .operation()
                    .named("$events")
                    .onInstancesOfType(Subscription.class)
                    .andAllowAllResponses();
        }

        // `$reconciliar` BORRA recursos publicados de cualquier tipo, así que exige exactamente eso:
        // el permiso completo sobre todos los tipos, y solo desde un cliente de sistema. Ningún
        // cliente del realm lo tiene concedido de fábrica — dárselo a alguien es un acto explícito.
        if (ambito.contexto() == AmbitoSmart.Contexto.SISTEMA
                && ambito.todosLosTipos()
                && ambito.permisos().size() == Permiso.values().length) {
            reglas.allow(nombre + " reconciliar")
                    .operation()
                    .named("$reconciliar")
                    .onServer()
                    .andAllowAllResponses();
        }
    }

    private static IAuthRuleBuilderRuleOpClassifier sobreElTipo(IAuthRuleBuilderRuleOp regla, AmbitoSmart ambito) {
        return ambito.todosLosTipos() ? regla.allResources() : regla.resourcesOfType(ambito.tipoDeRecurso());
    }
}
