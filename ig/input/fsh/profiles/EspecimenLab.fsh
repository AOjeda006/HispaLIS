Profile: EspecimenLab
Parent: Specimen
Id: especimen-lab
Title: "Espécimen de laboratorio"
Description: """
Muestra biológica recibida en el laboratorio, con su número de acceso.

El **número de acceso** (`accessionIdentifier`) es obligatorio: es el código con el que la muestra
circula físicamente por el laboratorio y el que une el tubo con el resultado. Sin él no hay
trazabilidad.

El **rechazo de muestra** no necesita extensión (§6.1): `status` admite `unsatisfactory` y
`condition` documenta el motivo. La invariante `hlis-esp-1` exige que un rechazo venga siempre
motivado — rechazar sin decir por qué obliga al peticionario a llamar por teléfono.
"""

* obeys hlis-esp-1

* accessionIdentifier 1..1 MS
* accessionIdentifier ^short = "Número de acceso con el que la muestra circula por el laboratorio"

* status MS
* status ^short = "`available` | `unavailable` | `unsatisfactory` | `entered-in-error`"

* type 1..1 MS
* type from TiposMuestra (extensible)
* type ^short = "Tipo de muestra (sangre, orina, exudado…)"

* subject 1..1 MS
* subject only Reference(PacienteLabES)

* request MS
* request only Reference(PeticionLab)

* receivedTime MS

* collection MS
* collection.collected[x] MS
* collection.collector MS
// El ayuno es elemento estándar en R5, no una extensión: `CodeableConcept` para «en ayunas / no en
// ayunas» o `Duration` para las horas exactas.
* collection.fastingStatus[x] MS
* collection.bodySite MS

* condition MS
* condition from MotivosRechazoMuestra (extensible)
* condition ^short = "Estado de la muestra: hemolizada, coagulada, insuficiente…"

* note MS


Invariant: hlis-esp-1
Description: "Un espécimen rechazado debe documentar el motivo del rechazo en `condition`."
Severity: #error
Expression: "status != 'unsatisfactory' or condition.exists()"
