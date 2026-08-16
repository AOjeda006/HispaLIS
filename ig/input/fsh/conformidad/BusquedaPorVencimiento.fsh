// Buscar declaraciones por su fecha límite. R5 no trae con qué.
//
// El ítem 48 pide que «se vea cuál se ha pasado de plazo». Con lo que da el núcleo de R5 no se puede:
// `Task` tiene dieciocho parámetros de búsqueda estándar y **ninguno** cae sobre
// `Task.restriction.period`, que es donde el estándar quiere que viva el plazo dentro del cual se
// busca cumplir la tarea. Los que hay cerca no sirven y conviene decir por qué:
//
//   `period`      → cubre `Task.executionPeriod`, que es cuándo se hizo el trabajo. No es el plazo.
//   `authored-on` → cuándo se abrió la tarea. Con un plazo por enfermedad, la fecha de apertura no
//                   permite deducir el vencimiento sin repetir el catálogo en el cliente.
//   `modified`    → cuándo se tocó por última vez. No dice nada del plazo.
//
// Sin este parámetro, la única forma de listar lo vencido sería descargar todas las declaraciones
// abiertas y mirarlas una a una en el cliente — que es lo que hace que nadie mire.
//
// Definir un `SearchParameter` propio es lo que una IG hace en este caso, y no una extensión del
// modelo: no se añade dato ninguno, se declara cómo se indexa uno que ya está.

Instance: BusquedaPorVencimiento
InstanceOf: SearchParameter
Usage: #definition
Title: "Búsqueda: vencimiento de la declaración"
Description: """
Permite `GET [base]/Task?vencimiento=lt2026-08-04` — las declaraciones cuyo plazo termina antes de esa
fecha.

Combinado con `business-status`, que sí es estándar, responde a la pregunta que importa:
**qué obligaciones se han pasado de plazo sin acusar**.

```
GET [base]/Task?business-status=PENDIENTE,ENVIADA&vencimiento=lt2026-08-04T12:00:00%2B02:00
```

Es un parámetro **propio de esta guía**, con URI canónica propia, como todo lo demás que aquí se
publica. Un servidor que sirva estos perfiles y no lo indexe seguirá siendo conforme a FHIR: lo que no
podrá es contestar esa pregunta.
"""

// Sin `id` explícito, SUSHI lo saca del nombre del bloque, que es PascalCase, y la página publicada
// diría otra cosa que la URL canónica (memoria técnica, §11.1).
* id = "notificacion-edo-vencimiento"
* url = "https://aojeda006.github.io/HispaLIS/fhir/SearchParameter/notificacion-edo-vencimiento"
* version = "0.1.0"
* name = "BusquedaPorVencimiento"
// ⚠️ `active` y NO `draft`, aunque la guía entera esté en `draft` y esto parezca una incoherencia.
// MEDIDO contra HAPI 8.10.1: `SearchParameterCanonicalizer` traduce el `status` del recurso y
// `SearchParamRegistryImpl` se queda SOLO con los `ACTIVE`. Un `SearchParameter` en `draft` se guarda,
// se publica, se lee por la API — y el servidor NO lo indexa, así que la búsqueda contesta
// «HAPI-0524: Unknown search parameter». Sin error, sin aviso: el parámetro está y no funciona.
// El `status` de un recurso de conformidad habla de la madurez de la DEFINICIÓN, y aquí la definición
// está cerrada aunque la guía siga creciendo. `experimental` sigue en `true`, que es lo que dice que
// esto es una simulación. Detalle en `docs/adr/adr-0029-un-searchparameter-en-draft-se-publica-y-no-se-indexa.md`.
* status = #active
* experimental = true
* date = "2026-08-10"
* publisher = "Andrés Ojeda Rodríguez"
* jurisdiction = urn:iso:std:iso:3166#ES "Spain"

* code = #vencimiento
* base[0] = #Task
* type = #date
* expression = "Task.restriction.period.end"

// ⚠️ OBLIGATORIO en cuanto hay `expression`, y es fácil no ponerlo: la invariante `spd-1` de R5 dice
// «if an expression is present, there SHALL be a processingMode», y sin él el validador oficial da
// ERROR aunque SUSHI compile sin una queja. Es el heredero de `xpathUsage` de R4, así que quien venga
// de allí busca un elemento que ya no existe y no echa de menos éste. `normal` es lo que corresponde:
// se indexa el valor tal cual, sin fonética ni tratamiento aparte.
* processingMode = #normal

// Los cuatro comparadores de fecha que tienen sentido aquí. `ap` («aproximadamente») se deja fuera a
// propósito: sobre un plazo legal, «aproximadamente antes del jueves» no significa nada.
* comparator[0] = #eq
* comparator[1] = #lt
* comparator[2] = #gt
* comparator[3] = #le
* comparator[4] = #ge
