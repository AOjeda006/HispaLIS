---
tipo: referencia
stack: [java, fhir, hapi]
aplica_a: []
revisado: 2026-08-12
tags: [adr, fhir, r5, hapi, parsers, robustez, procesos-de-fondo]
---

# ADR-0036: Lo que el parser tira sin decir nada revienta lejos

- **Estado:** aceptado
- **Fecha:** 2026-08-12

## Contexto

La `Subscription` dice con qué clave se firman sus notificaciones. No la clave: **su identificador**,
en `Subscription.parameter` (`adr-0027`). Al crear la suscripción del circuito se mandó esto:

```json
"parameter": [{"name": "identificador-de-clave", "valueString": "his-2026"}]
```

Que es la forma de `Parameters`, y es la que sale de la memoria de cualquiera que haya escrito diez
recursos FHIR. Pero **`Subscription.parameter.value` no es un tipo de elección**: es un `string` que
se llama `value`, a secas. `valueString` no existe en ese elemento, el parser lo descarta, y el
recurso se guarda `201 Created` con un parámetro **con nombre y sin valor**.

Dos capas más allá, el relay hacía:

```java
suscripcion.getParameter().stream()
        .filter(p -> PARAMETRO_DE_CLAVE.equals(p.getName()))
        .map(SubscriptionParameterComponent::getValue)   // ← null
        .findFirst()                                     // ← Optional.of(null) → NPE
```

`findFirst()` sobre un elemento nulo lanza `NullPointerException` **sin mensaje**. Y como el relay
envuelve la vuelta entera en un `catch (RuntimeException)` para que el planificador no se pare, el
resultado fue el peor posible: **ninguna suscripción volvió a recibir nada** —tampoco las bien
formadas, porque la vuelta se abortaba antes de llegar a ellas— y el log repetía cada dos segundos:

```
WARN RelayDeNotificaciones : La vuelta del relay de notificaciones ha fallado entera;
                             se reintenta. Causa: java.lang.NullPointerException
```

Sin traza, sin mensaje y sin decir qué suscripción. Tres decisiones razonables por separado —lenient
parsing, tragarse la excepción para no matar el planificador, registrar solo el mensaje— se sumaron
en un fallo silencioso y global.

## Decisión

**Tres cambios, uno por cada eslabón.**

1. **Un valor nulo se filtra donde se lee**, no se supone que no puede pasar:

   ```java
   .map(SubscriptionParameterComponent::getValue)
   .filter(Objects::nonNull)
   .findFirst()
   .orElse("");
   ```

   Sin clave, `EntregaFirmada` se niega a mandar sin firmar y la suscripción se corta con un motivo
   legible por `$status`. **El fallo se queda en la suscripción que lo provocó.**

2. **El `catch` de última instancia registra la traza entera.** Lo que llega ahí es, por definición,
   lo que nadie esperaba: un fallo de entrega tiene su propio camino y no pasa por ese `catch`.
   Registrar solo `e.toString()` de algo inesperado es no registrar nada.

3. **Un test que lo sostiene**: una suscripción con el parámetro sin valor y otra bien formada, y se
   comprueba que la primera se corta y la segunda recibe igual.

## Consecuencias

- El relay es robusto frente a una suscripción mal formada, que es lo que se puede escribir por la
  API con un `201` de vuelta.
- Un log más largo cuando algo revienta de verdad. Es el intercambio correcto: la traza se lee una
  vez y la ausencia de traza cuesta una tarde.
- Queda **sin resolver**, y anotado en `PLAN.md`, si el laboratorio debería además **rechazar al
  escribir** una `Subscription` cuyo parámetro de clave no tiene valor. Hay argumento para las dos:
  rechazar avisa antes, y aceptar y cortar deja el motivo escrito donde el suscriptor lo consulta.

## Alternativas descartadas

- **Parsear en modo estricto.** HAPI puede fallar ante un elemento desconocido. Suena bien y rompería
  la extensibilidad de FHIR por diseño: un cliente con una versión más nueva manda elementos que este
  servidor no conoce, y tirarle la petición es lo contrario de lo que el estándar pide. El problema
  no es que el parser sea permisivo; es que nadie miró el nulo.
- **Dejar que la excepción suba y mate el planificador.** Un relay parado del todo se nota antes, sí.
  También deja de entregar todo lo demás, que es exactamente el fallo que se está arreglando.
- **`Optional.ofNullable` sobre el resultado.** Equivalente en efecto y peor de leer: el nulo se
  filtra donde aparece, no se envuelve donde se usa.

## Lo reutilizable

1. **Un campo que el parser no reconoce se pierde sin ruido, y el `201` no significa que se haya
   guardado lo que mandaste.** Vale para FHIR, para JSON con Jackson y para cualquier deserializador
   permisivo. Al construir un recurso a mano, comprobar el **nombre exacto** del elemento en la
   especificación; y al leer un recurso ajeno, no dar por hecho que un elemento presente trae valor.
2. **`.map(...).findFirst()` es un `NullPointerException` esperando.** `Optional` no admite nulos:
   cualquier `Stream` cuyo `map` pueda devolver nulo necesita un `filter(Objects::nonNull)` antes.
3. **Un `catch` de última instancia sin traza convierte un fallo en un misterio.** Si algo se traga
   excepciones para sobrevivir, tiene que dejar dicho **todo** lo que se tragó.
4. **En un bucle que procesa muchos, el fallo de uno no puede ser el fallo de la vuelta.** La unidad
   de aislamiento tiene que ser el elemento, no la tanda; si no, el peor formado de todos decide por
   los demás.
5. **Un elemento llamado `value` no siempre es un `value[x]`.** En FHIR conviven los dos, y el hábito
   de escribir `valueString` es más fuerte que la comprobación.
