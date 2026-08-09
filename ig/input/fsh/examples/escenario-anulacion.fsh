// Anulación de una línea de petición.
//
// El caso de verdad: se pidió una prueba que ya no procede —el paciente se fue sin extraerse, el
// clínico se equivocó de perfil, la muestra se rechazó y no habrá nueva extracción— y esa línea
// tiene que dejar de bloquear el informe. Sin anular, el volante entero se queda esperando para
// siempre, porque un informe no se emite con líneas pendientes.
//
// Es el escenario del ítem 17 del hito 2. Todos los datos son SINTÉTICOS.

Instance: peticion-anulada
InstanceOf: PeticionLab
Usage: #example
Title: "Línea anulada: creatinina que ya no procede"
Description: """
`status = revoked` y **el motivo en `note`**.

⚠️ **R5 no da `statusReason` en `ServiceRequest`.** Lo tienen `MedicationRequest`, `Task` y
`CarePlan`, pero no este recurso: verificado contra `hl7.fhir.r5.core@5.0.0`. Las salidas eran tres
y solo una es honesta:

- **Una extensión propia** — descartada. La regla del proyecto es que una extensión propia hay que
  justificarla contra la tabla de §6.1, y aquí hay elemento estándar donde escribirlo.
- **`reason`** — descartada, y es la que más engaña: `ServiceRequest.reason` dice por qué se **pidió**
  la prueba, no por qué se anuló. Escribir ahí el motivo de la anulación corrompe el dato clínico.
- **`note`** — la elegida. Es texto libre y no se puede procesar, y eso es una pérdida real que
  queda dicha: un sistema que quiera contar anulaciones por causa **no puede** con esto. A cambio,
  ningún cliente lee un motivo en el sitio donde esperaba encontrar otra cosa.

Una línea anulada **no vuelve**: no se reactiva y no admite un espécimen nuevo. Si hay que repetir
la prueba, se registra otra línea.
"""
* status = #revoked
* intent = #order
* code.concept = CatalogoPruebas#CREA "Creatinina"
* requisition.system = "https://aojeda006.github.io/HispaLIS/sid/peticion"
* requisition.value = "P-2026-004512"
* subject = Reference(paciente-ejemplo)
* requester = Reference(facultativo-ejemplo)
* performer[0] = Reference(laboratorio-ejemplo)
* authoredOn = "2026-07-28T08:15:00+02:00"
* priority = #routine
* note[0].text = "Anulada a petición del servicio solicitante: la creatinina se pidió por duplicado en el mismo volante."
* note[0].time = "2026-07-28T10:47:00+02:00"
* note[0].authorReference = Reference(facultativo-ejemplo)


Instance: resultado-preliminar
InstanceOf: ResultadoLab
Usage: #example
Title: "Urea, medida y AÚN NO validada"
Description: """
Un resultado en `preliminary`: el analizador ya ha dado la cifra y **ningún facultativo la ha
firmado todavía**.

Existe como ejemplo porque es el estado que más se malinterpreta. Un `preliminary` es un dato
técnico correcto —tiene su unidad y su rango— del que el laboratorio **no responde**, y por eso no
entra en ningún `InformeLab` y no tiene ningún `ProcedenciaValidacion` apuntándole. Una aplicación
que lo enseñe sin decirlo está presentando como resultado clínico algo que puede cambiar.
"""
* status = #preliminary
* code = CatalogoPruebas#UREA "Urea"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-ejemplo)
* effectiveDateTime = "2026-07-28T08:41:00+02:00"
* valueQuantity.value = 38
* valueQuantity.unit = "mg/dL"
* valueQuantity.system = $UCUM
* valueQuantity.code = #mg/dL
* referenceRange[0].low.value = 17
* referenceRange[0].low.unit = "mg/dL"
* referenceRange[0].low.system = $UCUM
* referenceRange[0].low.code = #mg/dL
* referenceRange[0].high.value = 43
* referenceRange[0].high.unit = "mg/dL"
* referenceRange[0].high.system = $UCUM
* referenceRange[0].high.code = #mg/dL
