Profile: NotificacionEDO
Parent: Task
Id: notificacion-edo
Title: "Notificación de enfermedad de declaración obligatoria"
Description: """
Declaración a Salud Pública de un resultado que corresponde a una enfermedad de declaración
obligatoria.

**No es una funcionalidad opcional.** Todos los centros sanitarios de Andalucía, públicos *y
privados*, forman parte del Sistema de Vigilancia Epidemiológica de Andalucía (Decreto 66/1996), y
la declaración se tramita electrónicamente a Redalerta. Cuando se valida un resultado cuyo código
está en el catálogo EDO, la notificación es obligatoria por ley.

Se modela como `Task` y no con una extensión (§6.1) porque una notificación tiene ciclo de vida
propio: se crea, se envía, se acusa recibo y puede rechazarse. `focus` apunta al resultado que la
motiva y `businessStatus` lleva el estado frente a la administración, que no coincide con el
`status` del propio `Task`.

**No la crea un cliente.** La abre el laboratorio solo, al validarse un resultado que cae en el
catálogo EDO, y la va cerrando según contesta el destinatario. Un `POST` de fuera creando una de estas
declararía una enfermedad que nadie ha confirmado, así que la puerta está cerrada como en el resto de
recursos con agregado detrás.

El catálogo EDO y el formato de Redalerta se modelan de forma **verosímil, no fiel**: el contrato
real no es público.

⚠️ **Y una diferencia con la realidad que hay que decir en voz alta:** una declaración EDO de verdad
lleva **filiación** — Salud Pública tiene que poder localizar al caso para la encuesta
epidemiológica y para buscar el foco—. La de esta guía **no la lleva**, y no es un olvido: el proyecto
no manda datos de persona a ningún sistema externo, y el destinatario de aquí es simulado. Lo que
viaja es el código de la enfermedad y una referencia interna al caso. Quien tome esta guía como base
para una integración real tiene ahí su primer trabajo, y no es pequeño: es una cesión de datos de
salud, con su base jurídica y su registro de actividad.
"""

* status MS
* status ^short = "Cómo va la TAREA: `requested` | `in-progress` | `completed` | `rejected`"
* intent MS

// ⚠️ Los dos estados no son el mismo y no se pueden fundir. `status` habla de la tarea dentro del
// laboratorio; `businessStatus`, de la declaración frente a la administración. Un `Task` puede estar
// `in-progress` mientras la declaración está «enviada y sin acusar», y esa es exactamente la
// situación que hay que poder nombrar cuando alguien pregunte si se declaró en plazo. Con un solo
// elemento, «no lo hemos mandado» y «lo mandamos y no contestan» se escriben igual.
* businessStatus 1..1 MS
* businessStatus from EstadosDeDeclaracion (required)
* businessStatus ^short = "Estado de la declaración frente a Salud Pública. NO es `Task.status`"

* code 1..1 MS
* code ^short = "Tipo de tarea: notificación EDO"

// QUÉ ENFERMEDAD SE DECLARA, y va aquí y no en `code.text` porque `code.text` no se indexa. El
// destinatario tiene que poder leerla de un elemento codificado, y el laboratorio tiene que poder
// listar sus declaraciones por enfermedad sin abrir una a una. `reason` es `CodeableReference` en R5
// —en R4 eran `reasonCode` y `reasonReference`, dos elementos—, así que el código va en `.concept`.
//
// ⚠️ Y la vinculación va sobre `reason`, NO sobre `reason.concept`: SUSHI rechaza atar un `ValueSet`
// al `.concept` de un `CodeableReference` —«apply the binding directly to the CodeableReference
// element»—. Es una trampa de R5 con nombre propio: en R4 se ataba a un `CodeableConcept` y el camino
// era el natural.
* reason 1..1 MS
* reason from EnfermedadesDeclarables (required)
* reason.concept 1..1 MS
* reason.concept ^short = "Enfermedad de declaración obligatoria que el resultado confirma"

* focus 1..1 MS
* focus only Reference(ResultadoLab)
* focus ^short = "Resultado validado que obliga a declarar"

* for 1..1 MS
* for only Reference(PacienteLabES)

* requester MS
* requester only Reference(LaboratorioOrg)

* owner MS
* owner ^short = "Organismo de Salud Pública al que se declara"

// La modalidad de la enfermedad, dicha en el elemento estándar en vez de en una extensión (§6.1):
// urgente → `stat`, ordinaria → `routine`. Es lo que permite ordenar una bandeja de declaraciones sin
// tener que consultar el catálogo por cada fila.
* priority MS
* priority ^short = "`stat` para la declaración urgente, `routine` para la ordinaria"

// EL PLAZO. `restriction.period` es «el intervalo dentro del cual se busca que la tarea se cumpla»,
// que es literalmente la ventana legal. Se marca solo el final: el principio es cuándo nació la
// obligación y eso ya está en `authoredOn`, y repetirlo daría dos sitios que se pueden contradecir.
//
// ⚠️ R5 no trae `SearchParameter` sobre este elemento, así que la guía publica el suyo
// (`SearchParameter/notificacion-edo-vencimiento`, código `vencimiento`). Sin él, saber qué se ha
// pasado de plazo obliga a descargarlo todo y mirarlo fila a fila en el cliente.
* restriction MS
* restriction.period 1..1 MS
* restriction.period.end 1..1 MS
* restriction.period.end ^short = "Fecha límite legal para declarar, contada desde que el resultado quedó validado"

// EL ACUSE, que es lo único que convierte «lo mandamos» en «está declarado». Va como `Identifier` y
// no como cadena a propósito: el número de registro es de Salud Pública, no del laboratorio, y un
// `Identifier` lleva dentro de quién es. Con un `valueString` habría que saberlo de memoria.
* output MS
* output ^short = "El acuse de Salud Pública: su número de registro. Ausente mientras no haya contestado"
* output.value[x] only Identifier

* authoredOn 1..1 MS
* authoredOn ^short = "Cuándo nació la obligación, que es cuando el resultado quedó validado"
* lastModified MS
* note MS
