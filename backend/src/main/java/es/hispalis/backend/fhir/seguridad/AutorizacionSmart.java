package es.hispalis.backend.fhir.seguridad;

import ca.uhn.fhir.rest.api.server.RequestDetails;
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
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Provenance;

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

        return reglas.denyAll("Los scopes de este testigo no alcanzan a lo que se ha pedido.")
                .build();
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
        // `STORAGE_PRESTORAGE_RESOURCE_CREATED`. Sin la segunda regla, un testigo con
        // `user/Observation.u` pasaba la autorización de la operación y se llevaba un `403` al
        // escribir la procedencia, con un mensaje que no dice qué recurso lo provocó.
        // `andAllowAllResponsesWithAllResourcesAccess()` **tampoco basta**: eso abre la respuesta,
        // no la escritura. Medido contra HAPI 8.10.1.
        //
        // **La operación llevaba desde el ítem 18 sin poder ejecutarse con la seguridad puesta** y
        // no lo veía nadie: los tests de integración la apagan y ningún cliente llama todavía a
        // `$validar` —la web no tiene pantalla de validación—. Apareció al recorrer el circuito v2
        // contra el `compose`.
        //
        // Conceder la creación de `Provenance` no abre ninguna puerta: lo que se escribe es efecto
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
                    .create()
                    .resourcesOfType(Provenance.class)
                    .withAnyId();
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
