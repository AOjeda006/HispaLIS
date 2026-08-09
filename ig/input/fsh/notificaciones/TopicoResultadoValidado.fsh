// El tópico de notificación del laboratorio: «un resultado ha pasado a validado».
//
// ⚠️ ESTE RECURSO NO EXISTE EN R4, y no es un detalle de versión: es OTRO MODELO.
//
//   R4  ── la `Subscription` lleva dentro `criteria`, una cadena de búsqueda («Observation?status=
//          final») que cada cliente escribe a mano contra el servidor al que se suscribe. El criterio
//          es del suscriptor, no está publicado en ninguna parte, y dos clientes que quieran lo mismo
//          lo escriben distinto.
//   R5  ── el criterio se saca de la `Subscription` y se publica como recurso de conformidad APARTE,
//          este `SubscriptionTopic`. La `Subscription` ya solo dice «quiero ESE tópico, entrégamelo
//          aquí». El servidor publica qué se puede escuchar; el cliente elige de esa lista.
//
// La consecuencia práctica: cualquier ejemplo, tutorial o respuesta de IA sobre `Subscription` que
// hable de `criteria` está describiendo R4 y aquí no compila siquiera — el elemento no existe.

Instance: TopicoResultadoValidado
InstanceOf: SubscriptionTopic
Usage: #definition
Title: "Tópico: resultado validado"
Description: """
Lo que este laboratorio deja escuchar: el momento en que un resultado deja de ser una cifra del
analizador y pasa a ser un resultado del que responde un facultativo.

**El disparador es el cambio de estado, no el estado.** Se pide que el recurso esté en `final`
*ahora* y que **no** lo estuviera *antes*: sin esa segunda condición, cualquier reescritura posterior
del mismo `Observation` —una corrección de la unidad impresa, un `performer` que faltaba— volvería a
notificar un hecho que ya se contó, y quien lo reciba no tiene forma de distinguirlo del primero.

**Por qué `final` y no `preliminary`.** Una cifra recién salida del analizador puede cambiar. Publicar
al exterior el momento de la medida sería invitar a que alguien actúe sobre un dato que todavía no
firma nadie; el hito 1 ya tomó esa decisión en la proyección y aquí se sostiene.
"""

// El `id` explícito no es decorativo: sin él SUSHI lo saca del nombre del bloque —que es
// PascalCase— y la URL publicada no coincidiría con la canónica de abajo (`ig/CLAUDE.md`).
* id = "resultado-validado"
* url = "https://aojeda006.github.io/HispaLIS/fhir/SubscriptionTopic/resultado-validado"
* version = "0.1.0"
* name = "TopicoResultadoValidado"
* status = #draft
* experimental = true
* date = "2026-08-09"
* publisher = "Andrés Ojeda Rodríguez"
* purpose = """
Que el HIS del hospital y los sistemas del peticionario se enteren de que hay resultado **sin
sondear la API cada minuto**, y sin que el laboratorio tenga que saber quiénes son ni mantener una
lista de destinatarios en su código.
"""

* resourceTrigger[0].description = "Un `Observation` de este laboratorio pasa a `final`."
* resourceTrigger[0].resource = "http://hl7.org/fhir/StructureDefinition/Observation"
// `create` además de `update` porque un resultado puede nacer ya validado —el motor de integración
// recibe por HL7 v2 resultados que el sistema de origen firmó antes de mandarlos—, y ese caso es
// exactamente igual de notificable que el que se firma aquí.
* resourceTrigger[0].supportedInteraction[0] = #create
* resourceTrigger[0].supportedInteraction[1] = #update
* resourceTrigger[0].queryCriteria.previous = "status:not=final"
* resourceTrigger[0].queryCriteria.current = "status=final"
// Las dos condiciones a la vez: es lo que convierte «está en final» en «acaba de pasar a final».
* resourceTrigger[0].queryCriteria.requireBoth = true
// En un alta no hay estado anterior contra el que preguntar, y hay que decir qué se hace con eso o
// queda a discreción del servidor. Se cuenta como aprobada: lo que no existía tampoco estaba en
// `final`, que es justo lo que dice el criterio `previous`.
* resourceTrigger[0].queryCriteria.resultForCreate = #test-passes
// Y en un borrado, como suspendida. Aquí no se borran resultados —el verbo está cerrado (ADR-0014)—
// así que la rama es inalcanzable; se declara igual, porque un tópico que deja el caso sin decir
// depende de con qué servidor se implemente.
* resourceTrigger[0].queryCriteria.resultForDelete = #test-fails

* notificationShape[0].resource = "http://hl7.org/fhir/StructureDefinition/Observation"
// Sin `include` ni `revInclude`: el paciente, la muestra y la petición NO viajan con la
// notificación. Quien la reciba resuelve las referencias contra la API, con su testigo y con el
// consentimiento aplicado; arrastrarlas aquí las entregaría sin ninguna de las dos cosas.
