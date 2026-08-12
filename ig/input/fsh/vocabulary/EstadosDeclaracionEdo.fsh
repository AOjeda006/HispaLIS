// En qué punto va una declaración frente a Salud Pública.
//
// Es el vocabulario que el ítem 47 dejó pendiente a propósito: mientras no existiera el notificador,
// el ejemplo de `NotificacionEDO` llevaba el estado en `businessStatus.text` porque nadie conocía
// todavía el ciclo. Ya se conoce, y va codificado.
//
// ⚠️ NO es lo mismo que `Task.status`, y confundirlos es el error caro de este recurso. `Task.status`
// dice cómo va la TAREA dentro del laboratorio —abierta, en curso, terminada—; esto dice cómo va la
// DECLARACIÓN frente a la administración. Un `Task` en `in-progress` puede estar «enviada y sin
// acusar», que es un estado del que hay que responder y no aparece en ningún vocabulario de FHIR.
//
// Los cuatro son estados por los que se pasa de verdad, y la distinción que más importa es la de los
// dos del medio: «lo mandamos y no contestan» y «contestaron que no» son cosas distintas, y fundirlas
// deja al laboratorio sin poder decir cuál de las dos le pasó.

CodeSystem: EstadosDeclaracionEdo
Id: estados-declaracion-edo
Title: "Estados de una declaración a Salud Pública"
Description: """
El ciclo de vida de una notificación EDO frente a la administración, que **no coincide** con el
`status` del `Task` que la transporta.

Se recorre en un solo sentido y no vuelve atrás: una declaración acusada no se reabre. Si hubiera que
rectificar lo declarado, la rectificación es otra declaración —igual que una línea de petición anulada
no se reactiva—, porque el registro de qué se dijo y cuándo es justamente lo que hay que conservar.

**El paso a `ACUSADA` solo lo da un acuse.** Un envío que sale sin error de red no es una declaración
hecha: si el día de mañana hay que demostrar que se declaró, lo que se enseña es el número de registro
que devolvió Salud Pública, no una línea de log diciendo que se intentó.
"""

* ^caseSensitive = true
* ^content = #complete
* ^valueSet = Canonical(EstadosDeDeclaracion)

* #PENDIENTE "Pendiente de enviar" "La obligación está registrada y todavía no ha salido. Es también donde se queda una declaración cuyo envío falló: el destinatario no la tiene."
* #ENVIADA "Enviada, sin acuse" "Salió y el destinatario la recibió, pero no ha devuelto número de registro. No cuenta como declarada."
* #ACUSADA "Acusada por Salud Pública" "Hay número de registro. Es el único estado en el que la obligación legal está cumplida."
* #RECHAZADA "Rechazada" "Salud Pública ha contestado que no la admite, con su motivo. Es una respuesta, no un fallo del canal, y por eso no se reintenta sola."


// ⚠️ El título NO puede ser el mismo que el del `CodeSystem` de arriba. El IG Publisher lo trata
// como **error**: «There are multiple resources with the same title […]. This is not allowed because
// it produces duplicate entries in the table of contents». Es de las pocas cosas que ni SUSHI ni el
// validador oficial ven, porque no es un problema del recurso sino de la guía que lo publica.
ValueSet: EstadosDeDeclaracion
Id: estados-de-declaracion
Title: "Estados admitidos en una declaración a Salud Pública"
Description: """
Los cuatro de `CodeSystem/estados-declaracion-edo`. Es el conjunto al que enlaza
`NotificacionEDO.businessStatus`, y el enlace es **`required`**.

`required` sobre un conjunto propio parece contradecir la regla de esta guía —nada de `required` sobre
vocabularios que en la práctica no están cerrados—, pero no es el mismo caso: aquellos son
vocabularios ajenos que crecen sin avisar; este es la **máquina de estados del propio laboratorio**, y
un estado fuera de ella no es una declaración de esta guía sino otra cosa. Lo que sí es un error es
dejarlo abierto: con un enlace laxo, dos implementaciones escribirían «pendiente» y «PDTE» y nadie
podría preguntar cuántas declaraciones van sin acusar.
"""

* include codes from system EstadosDeclaracionEdo
