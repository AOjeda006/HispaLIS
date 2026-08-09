// Escenario: el HIS del hospital quiere enterarse de los resultados validados.
//
// Tres piezas y en este orden: a qué se suscribe, qué le llega por el canal, y qué contesta el
// laboratorio cuando se le pregunta cómo va la suscripción.
//
// ⚠️ La pieza que más se equivoca al venir de R4 no es ninguna de las tres: es dónde vive el
// criterio. Aquí la `Subscription` NO lo lleva — ver `SubscriptionTopic/resultado-validado`.

Instance: suscripcion-del-his
InstanceOf: Subscription
Usage: #example
Title: "Suscripción del HIS a los resultados validados"
Description: """
Una suscripción al tópico del laboratorio. Fíjate en lo que **no** hay: ningún `criteria`, ninguna
cadena de búsqueda. Solo `topic`, que es una canónica, y a dónde entregar.

`content = #id-only` es la decisión que importa y no es de rendimiento: con `full-resource` el
laboratorio estaría mandando la historia clínica por un canal saliente a un sistema que no la ha
pedido en esa petición y sin testigo por delante. Con `id-only` viaja **la identidad del recurso y
nada más**; quien la reciba va a buscarlo a la API con su testigo, y allí se le aplica el
consentimiento del paciente como a cualquier otra lectura.
"""
* status = #active
* name = "HIS del Hospital Virgen del Rocío"
* topic = "https://aojeda006.github.io/HispaLIS/fhir/SubscriptionTopic/resultado-validado"
* reason = "El HIS incorpora a la historia del paciente los resultados que el laboratorio da por definitivos."
* channelType = http://terminology.hl7.org/CodeSystem/subscription-channel-type#rest-hook
* endpoint = "https://his.example.org/hispalis/notificaciones"
* contentType = #application/fhir+json
* content = #id-only
// Cuántos recursos como mucho caben en una notificación. Sin tope, un reproceso de mil resultados
// saldría en un solo cuerpo que el receptor probablemente rechace por tamaño.
* maxCount = 20
// ⚠️ Aquí NO va el secreto compartido. `Subscription.parameter` es el sitio donde la documentación
// habitual mete una cabecera `Authorization`, y es un error: el recurso se lee por la API como
// cualquier otro, así que la credencial del receptor quedaría publicada a todo el que tenga permiso
// de lectura sobre `Subscription`. Este laboratorio **firma** la notificación en vez de
// autenticarse con ella; el secreto vive en la configuración del servidor. Ver `uso-de-la-api.html`.
// Lo que sí va aquí es CUÁL de las claves compartidas se usa para firmar, que es un identificador y
// no un secreto: publicarlo no permite firmar nada.
* parameter[0].name = "identificador-de-clave"
* parameter[0].value = "his-2026"


Instance: estado-suscripcion-del-his
InstanceOf: SubscriptionStatus
Usage: #example
Title: "$status de la suscripción, después de un fallo de entrega"
Description: """
Lo que devuelve `GET [base]/Subscription/suscripcion-del-his/$status`.

⚠️ **Aquí está el elemento que en R4 estaba en otro sitio.** R4 tenía `Subscription.error`, una
cadena dentro del propio recurso. **En R5 `Subscription` no tiene `error`**: el estado sigue siendo
`error`, pero el motivo se cuenta aquí, en `SubscriptionStatus.error`, que es codificado y no texto
libre. Buscar `Subscription.error` en R5 y no encontrarlo lleva derecho a inventarse una extensión
para algo que el estándar ya modela.

`eventsSinceSubscriptionStart` no se pone a cero al fallar: cuenta los hechos que ocurrieron, no los
que se entregaron. Es lo que permite al receptor saber **cuántos se ha perdido** cuando vuelve.
"""
* status = #error
* type = #query-status
* eventsSinceSubscriptionStart = 12
* subscription = Reference(suscripcion-del-his)
* topic = "https://aojeda006.github.io/HispaLIS/fhir/SubscriptionTopic/resultado-validado"
* error[0] = http://terminology.hl7.org/CodeSystem/subscription-error#no-response "No response from endpoint"
* error[0].text = "Cuatro intentos sin respuesta del receptor (Connection refused). La suscripción queda en `error` y deja de intentarse hasta que alguien la reactive."


// El primer elemento de toda notificación, que es lo que le da sentido al resto del `Bundle`.
Instance: estado-en-la-notificacion
InstanceOf: SubscriptionStatus
Usage: #inline
* status = #active
* type = #event-notification
* eventsSinceSubscriptionStart = 1
* notificationEvent[0].eventNumber = 1
* notificationEvent[0].timestamp = "2026-07-28T13:20:04+02:00"
* notificationEvent[0].focus = Reference(resultado-tsh)
* subscription = Reference(suscripcion-del-his)
* topic = "https://aojeda006.github.io/HispaLIS/fhir/SubscriptionTopic/resultado-validado"


Instance: notificacion-resultado-validado
InstanceOf: Bundle
Usage: #example
Title: "Lo que viaja por el canal"
Description: """
La notificación entera, tal y como sale del laboratorio. **Lee la segunda entrada:** tiene `fullUrl`
y una petición `GET`, y **no tiene recurso dentro**. Ni el valor de la TSH, ni el nombre del
paciente, ni su número de historia. Eso es `id-only`, y es el invariante 6 del proyecto aplicado a
un canal saliente.

La primera entrada sí lleva recurso, y tiene que llevarlo: es el `SubscriptionStatus` que dice de
qué suscripción es esto, de qué tópico y qué número de evento — que es lo que permite al receptor
detectar que se ha perdido el 7 sin tener que preguntar.
"""
* type = #subscription-notification
* timestamp = "2026-07-28T13:20:04+02:00"
* entry[0].fullUrl = "urn:uuid:5b1b7bd4-9d1f-4c9e-9e04-9a2f7b1c33a1"
* entry[0].resource = estado-en-la-notificacion
* entry[1].fullUrl = "https://aojeda006.github.io/HispaLIS/fhir/Observation/resultado-tsh"
* entry[1].request.method = #GET
* entry[1].request.url = "Observation/resultado-tsh"
