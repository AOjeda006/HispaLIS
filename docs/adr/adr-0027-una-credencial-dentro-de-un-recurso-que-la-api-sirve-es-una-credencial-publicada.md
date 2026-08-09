---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-09
tags: [adr, seguridad, fhir, subscription, webhooks, secretos, firma, hmac]
---

# ADR-0027: Una credencial dentro de un recurso que la API sirve es una credencial publicada

- **Estado:** aceptado
- **Fecha:** 2026-08-09

## Contexto

Al implementar las notificaciones salientes de `Subscription` (ítem 44) hacía falta que el receptor
pudiera distinguir una notificación del laboratorio de una inventada por cualquiera que conociese su
URL. Es el problema clásico de un *webhook*, y la respuesta que da casi toda la documentación —la de
FHIR incluida, y la de la mayoría de los servicios que emiten *webhooks*— es **meter una cabecera
`Authorization: Bearer …` en la propia suscripción**: en R5, `Subscription.parameter`; en R4,
`Subscription.channel.header`.

El problema es que en FHIR **`Subscription` es un recurso más de la API**. Se lee con un `GET`, sale
en las búsquedas, y su historial de versiones **no se borra**. Una credencial escrita ahí queda:

- legible para cualquier cliente con permiso de lectura sobre el tipo — que no es el mismo conjunto
  de gente que puede administrar suscripciones;
- guardada en `_history`, donde sigue estando después de «corregirla»;
- exportada con el recurso en cualquier copia, volcado o depuración.

Y no se nota. El sistema funciona igual de bien con la credencial publicada que sin ella, así que
nada avisa hasta que alguien la usa.

Lo mismo vale fuera de FHIR: es el patrón general de **guardar un secreto en un objeto de dominio que
el sistema expone**. Un `Webhook` con su `secret` en la tabla que sirve la API de administración, un
`Integration` con su `apiKey` en el JSON de configuración que se devuelve al pintar la pantalla de
ajustes. El recurso está pensado para leerse; el secreto, para no leerse.

## Decisión

**Un secreto compartido nunca vive en un recurso que la API sirve. Vive en la configuración del
servidor, y el recurso guarda como mucho su identificador.**

Y en vez de autenticarse con un portador, **se firma el mensaje**: HMAC-SHA256 sobre
`<marca-de-tiempo>.<cuerpo>`, con la marca de tiempo también en una cabecera para que el receptor
pueda descartar reenvíos. El receptor recalcula y compara con una comparación de tiempo constante.

En este proyecto:

```
Subscription.parameter[identificador-de-clave] = "his-2026"     ← esto SÍ se publica: es un nombre
hispalis.notificaciones.secretos.his-2026 = <la clave>           ← esto NO sale del servidor
X-HispaLIS-Momento: 1785242426
X-HispaLIS-Firma:   his-2026=sha256:<hex>
```

**Sin clave configurada para esa suscripción, la notificación no sale.** Mandarla sin firmar dejaría
al receptor sin poder distinguirla de una inventada, que es justo lo que esto viene a resolver.

## Consecuencias

- **La credencial deja de estar en la superficie de lectura.** Quien pueda leer `Subscription` ve un
  identificador de clave, que no permite firmar nada.
- **Se gana integridad, que un portador no da.** Un `Bearer` demuestra quién llama; la firma demuestra
  además que el cuerpo no se ha tocado por el camino.
- **Se gana resistencia al reenvío**, porque la marca de tiempo entra en lo firmado. Sin ella, una
  notificación capturada vale para siempre.
- **Cuesta trabajo al receptor**, que ya no puede limitarse a comparar una cabecera: tiene que
  calcular el HMAC. Es la contrapartida aceptada, y se compensa dándole una implementación de
  referencia (`simuladores/receptor/`) que además sirve de prueba del contrato desde el otro lado.
- **Rotar la clave es cambiar configuración**, no reescribir recursos: el identificador sigue igual y
  el mapa del servidor pasa a apuntar a otro valor. Con la credencial dentro del recurso, rotarla
  obliga a un `PUT` que además deja la anterior en el historial.
- **El emisor no puede «recordar» la clave por el suscriptor.** Si alguien da de alta una suscripción
  con un identificador de clave que el servidor no conoce, no se entrega y hay que configurarlo.
  Es deliberado: preferible que falle en el alta a que salga sin firmar.

## Alternativas consideradas

- **`Authorization: Bearer` en `Subscription.parameter`** — es lo que dice la documentación habitual.
  Descartada por lo de arriba: publica la credencial a todo el que pueda leer el recurso, y la deja
  para siempre en `_history`.
- **Cifrar el valor dentro del recurso** — mueve el problema: hace falta una clave para descifrar,
  que vuelve a estar en la configuración, y encima deja un campo que *parece* legible y no lo es.
- **mTLS entre laboratorio y receptor** — es mejor que las dos anteriores y no se descarta para un
  despliegue real, pero exige una PKI a los dos lados y no cabe en el alcance de una simulación. Y no
  resuelve la integridad del cuerpo frente a un intermediario que termine el TLS.
- **No autenticar, y que el receptor solo acepte de una IP** — no distingue una notificación legítima
  de otra fabricada desde la misma red, y las redes internas son exactamente donde eso pasa.
