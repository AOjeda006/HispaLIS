ValueSet: MotivosRechazoMuestra
Id: motivos-rechazo-muestra
Title: "Motivos de rechazo de una muestra"
Description: """
Por qué el laboratorio rechaza una muestra. Se usa en `EspecimenLab.condition`, y la invariante
`hlis-esp-1` exige que un espécimen con `status = unsatisfactory` lleve al menos uno: rechazar sin
motivo obliga al peticionario a llamar por teléfono.

**Todos los códigos son de HL7 Terminology, ninguno inventado.** Se combinan dos sistemas porque
ninguno cubre solo lo que hace falta:

- `RejectionCriterion` aporta los motivos propiamente dichos de rechazo, incluidos **volumen
  insuficiente** y **contenedor roto**, que son de los más frecuentes en la práctica.
- `specimenCondition` —el sistema al que R5 ata `Specimen.condition`— aporta **contaminada** y
  **autolizada**, que el anterior no tiene. Sus códigos de temperatura (`COOL`, `FROZ`, `ROOM`) no se
  incluyen: describen una condición, no un rechazo.
"""

* $CRITERIOS_RECHAZO#hemolized "hemolized specimen"
* $CRITERIOS_RECHAZO#insufficient "insufficient specimen volume"
* $CRITERIOS_RECHAZO#broken "broken specimen container"
* $CRITERIOS_RECHAZO#clotted "specimen clotted"
* $CRITERIOS_RECHAZO#wrong-temperature "specimen temperature inappropriate"

* $CONDICION_MUESTRA_HL7#CON "Contaminated"
* $CONDICION_MUESTRA_HL7#AUT "Autolyzed"
