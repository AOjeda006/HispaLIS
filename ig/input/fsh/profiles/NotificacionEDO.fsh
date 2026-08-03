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

El catálogo EDO y el formato de Redalerta se modelan de forma **verosímil, no fiel**: el contrato
real no es público.

> La generación automática de estas notificaciones pertenece al hito 3. Aquí se define el contrato.
"""

* status MS
* intent MS

* businessStatus MS
* businessStatus ^short = "Estado de la declaración frente a Salud Pública"

* code 1..1 MS
* code ^short = "Tipo de tarea: notificación EDO"

* focus 1..1 MS
* focus only Reference(ResultadoLab)
* focus ^short = "Resultado validado que obliga a declarar"

* for 1..1 MS
* for only Reference(PacienteLabES)

* requester MS
* requester only Reference(LaboratorioOrg)

* owner MS
* owner ^short = "Organismo de Salud Pública al que se declara"

* authoredOn 1..1 MS
* lastModified MS
* note MS
