Profile: PeticionLab
Parent: ServiceRequest
Id: peticion-lab
Title: "Petición de laboratorio"
Description: """
Línea de una petición analítica: una prueba o un perfil solicitado sobre un paciente.

Varias líneas de la misma petición comparten el número de `requisition`, que es lo que el
laboratorio y el peticionario llaman «la petición» en la conversación diaria.

⚠️ **R5:** `code` es `CodeableReference`, no `CodeableConcept` como en R4, y `reason` fusiona en un
solo elemento el `reasonCode` y el `reasonReference` de R4. Cualquier ejemplo de R4 copiado sin
mirar produce aquí un JSON que no valida.
"""

* status MS
* intent MS

// El código de la prueba se expresa como concepto, no como referencia a una `ActivityDefinition`:
// el catálogo del laboratorio es terminología, no un recurso de definición.
* code 1..1 MS
// El binding va sobre el CodeableReference entero: en R5 no se puede atar el `.concept` de
// dentro, y ponerlo ahi es error de compilacion.
* code from PruebasDelCatalogo (extensible)
* code.concept 1..1 MS
* code.concept ^short = "Prueba o perfil solicitado, del catálogo del laboratorio"

// Número que agrupa todas las líneas de una misma petición (§6.1: elemento estándar, sin extensión).
* requisition 1..1 MS

* subject 1..1 MS
* subject only Reference(PacienteLabES)

* requester 1..1 MS
* requester only Reference(FacultativoLab or PractitionerRole or LaboratorioOrg)

* performer MS
* performer only Reference(LaboratorioOrg or FacultativoLab or PractitionerRole)

* specimen MS
* specimen only Reference(EspecimenLab)

// En R5 `reason` es `CodeableReference`: admite el código diagnóstico (CIE-10-ES) o la referencia a
// la `Condition` que motiva la petición, en el mismo elemento.
* reason MS

* authoredOn 1..1 MS
* priority MS
* occurrence[x] MS
* note MS
