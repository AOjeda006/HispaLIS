---
tipo: referencia
stack: [java, spring, hapi]
aplica_a: []
revisado: 2026-08-12
tags: [adr, autorizacion, smart-on-fhir, hapi, interceptores, tests]
---

# ADR-0033: Autorizar la operación no autoriza la segunda vez que escribe

- **Estado:** aceptado
- **Fecha:** 2026-08-12

## Contexto

`POST /Observation/{id}/$validar` firma un resultado. Además del `Observation`, escribe un
`Provenance` por firma: la constancia de quién firmó y cuándo. La autorización SMART lo tenía
contemplado — está escrito en `AutorizacionSmart` desde el ítem 35, con su comentario y su test:

```java
reglas.allow(nombre + " validar").operation().named("$validar")
        .onInstancesOfType(Observation.class).andAllowAllResponses();
reglas.allow(nombre + " procedencia de la validación")
        .create().resourcesOfType(Provenance.class).withAnyId();   // ← create
```

Contra el `compose`, con la seguridad puesta y un potasio de 7,5 mmol/L:

```
1ª firma (COL12345) → 200, status=preliminary
2ª firma (COL41902) → 403  HAPI-0333: Access denied by rule:
                           Los scopes de este testigo no alcanzan a lo que se ha pedido.
```

Y en el log del backend, la línea que lo sitúa:

```
ERROR BaseInterceptorService : Exception thrown by interceptor for pointcut
      STORAGE_PRESTORAGE_RESOURCE_UPDATED: ForbiddenOperationException: HAPI-0333 …
```

`…_UPDATED`, no `…_CREATED`. **La primera firma da de alta una procedencia; la segunda hace que el
caso de uso reescriba las que ya había**, porque las vuelca todas al proyectar el agregado firmado.
Para el interceptor, eso ya no es un alta: es una modificación, y la regla no la cubría.

El efecto no era cosmético. Un resultado crítico exige dos firmas, así que **un crítico no se podía
terminar de validar contra la pila con seguridad**: se quedaba en `preliminary` para siempre y, por
tanto, fuera de cualquier informe. Justo el resultado por el que se llama por teléfono.

**Por qué ningún test lo veía**, que es la parte que más enseña:

| Clase de test | Umbral crítico | Seguridad | Qué firma escribe |
|---|---|---|---|
| `DobleValidacionTest` | sí, potasio | **apagada** | las dos, sin interceptor delante |
| `SeguridadSmartTest` | **no**, catálogo sin umbrales | encendida | solo la primera: con una basta |

Cada mitad de la condición vivía en una clase y ninguna tenía las dos. Ochenta tests de seguridad y
siete de doble validación, todos en verde, y el camino que cruza ambas no lo recorría nadie.

## Decisión

**`write()`, no `create()`.** La regla cubre el alta y la modificación del `Provenance` que la
operación escribe:

```java
reglas.allow(nombre + " procedencia de la validación")
        .write().resourcesOfType(Provenance.class).withAnyId();
```

Y un test que sostiene las dos condiciones a la vez: `DobleValidacionConSeguridadTest` — umbral
crítico **y** seguridad encendida, en la misma clase, con dos testigos distintos.

## Consecuencias

- Un crítico se valida entero con la seguridad puesta, que era el comportamiento que el ítem 46
  daba por hecho.
- La concesión no abre ninguna puerta nueva. Un cliente sigue sin poder escribir un `Provenance` por
  su cuenta: `ProveedorDeProcedencia` rechaza `POST` y `PUT`, y el mismo test lo comprueba con el
  mismo testigo. Lo que se autoriza es el efecto de un acto ya autorizado, no un verbo suelto.
- Queda una clase de test cuya razón de ser es **cruzar** dos configuraciones que antes eran
  paralelas. Es más cara de mantener que las dos que cruza, y ese es su precio.

## Alternativas descartadas

- **Que `ValidarResultado` solo escriba la procedencia nueva.** Sería menos escritura, pero cambia el
  modelo: la proyección deja de ser «vuelca el agregado» y pasa a «calcula el delta». El día que una
  procedencia se corrija en el dominio, la proyección no se enteraría. La reescritura completa es lo
  que hace que el reconciliador tenga sentido.
- **`andAllowAllResponsesWithAllResourcesAccess()` en la regla de la operación.** No es lo que hace:
  abre la **respuesta**, no la escritura. Medido contra HAPI 8.10.1 — se probó antes de la regla de
  `create()` y por eso está descartado dos veces.
- **Apagar la autorización para las escrituras internas de la operación** (por ejemplo, escribir con
  `SystemRequestDetails`). Es lo que hace la traza de acceso, y allí está justificado porque la traza
  la escribe el servidor **después** de contestar. Aquí no: la procedencia es parte de lo que el
  usuario pidió, y sacarla del control de acceso la volvería invisible al día que la regla cambie.

## Lo reutilizable

1. **Autorizar una operación no autoriza lo que la operación escribe** —y no lo autoriza *por verbo*.
   Con un motor de autorización que evalúa recurso a recurso, hay que enumerar cada tipo **y cada
   verbo** que el camino toca. `create` y `update` son dos permisos distintos aunque los escriba el
   mismo código.
2. **La segunda vez no es la primera otra vez.** Un caso de uso idempotente en su resultado no lo es
   en sus verbos: la primera ejecución crea y la segunda modifica. Los caminos que se prueban una
   sola vez esconden exactamente esta clase de fallo.
3. **Un fallo puede necesitar dos condiciones que viven en dos clases de test distintas.** Cuando una
   suite tiene «la clase que enciende X» y «la clase que configura Y», el cruce X∧Y **no está
   probado** por mucho que ambas estén en verde. Merece la pena listar las dimensiones de
   configuración de la suite y mirar qué combinaciones no existen.
4. **Y por eso hace falta recorrer el sistema montado.** Los tres fallos que los 287 tests no vieron
   —este, `adr-0031` y `adr-0032`— aparecieron en el primer recorrido de extremo a extremo contra el
   `compose`. No es que los tests fueran malos: es que un test elige su configuración y el sistema
   montado no.

## Apéndice: la misma lección, tercera vez

El mismo recorrido destapó otra cara de (1), y merece quedar escrita porque la forma es distinta:
**autorizar el recurso tampoco autoriza la operación**, ni siquiera cuando la operación no hace más
que leerlo. Un cliente con `system/Subscription.crs` creaba su suscripción y se llevaba un `403` al
llamar a `$status` — la operación con la que un suscriptor se entera de por qué le falló una entrega
y de cuántos eventos se perdió mientras estaba caído. `$status` y `$events` están declaradas
`idempotent = true` y ahora se conceden con el permiso de **leer** `Subscription`, con la misma
forma que `$validar` con el de actualizar `Observation`.

Regla práctica que resume las dos caras: con un motor de autorización que evalúa recurso a recurso,
**cada operación necesita su regla explícita** —de entrada por lo que ejecuta y de salida por lo que
escribe—, y ninguna de las dos se deduce del permiso sobre el tipo.
