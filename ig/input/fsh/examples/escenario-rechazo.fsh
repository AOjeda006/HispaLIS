// Muestra rechazada. Es el ejemplo que demuestra la invariante `hlis-esp-1`: un espécimen con
// `status = unsatisfactory` tiene que decir por qué, o el peticionario acaba llamando por teléfono.
//
// Es también el escenario del invariante de negocio del backend (criterio de aceptación 6): de un
// espécimen rechazado NO puede salir un resultado.

Instance: especimen-rechazado
InstanceOf: EspecimenLab
Usage: #example
Title: "Muestra rechazada por hemólisis"
Description: """
Espécimen inservible. `status = unsatisfactory` más el motivo en `condition`, que es lo que la
invariante `hlis-esp-1` exige.

El rechazo no necesita extensión: `Specimen.status` ya admite `unsatisfactory` y `condition` es
`0..*`. Verificado contra el paquete canónico antes de dar por buena la necesidad de una extensión.
"""
* status = #unsatisfactory
* accessionIdentifier.system = "https://aojeda006.github.io/HispaLIS/sid/acceso"
* accessionIdentifier.value = "26-0198512"
* type = $SCT#122555007
* subject = Reference(paciente-ejemplo)
* receivedTime = "2026-07-29T09:14:00+02:00"
* collection.collectedDateTime = "2026-07-29T08:52:00+02:00"
* condition[0] = $CRITERIOS_RECHAZO#hemolized "hemolized specimen"
* condition[1] = $CRITERIOS_RECHAZO#insufficient "insufficient specimen volume"
* note[0].text = "Tubo con hemólisis marcada y volumen por debajo del mínimo. Se solicita nueva extracción."
