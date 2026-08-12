// La exportación masiva de una cohorte, declarada por escrito.
//
// La IG de Bulk Data Access ya define `$export` sobre `Group`, así que publicar aquí una
// `OperationDefinition` propia necesita justificarse. La justificación es que esta operación **no hace
// lo mismo** que la del estándar, y las diferencias no son de detalle:
//
//   1. Lo que sale va SEUDONIMIZADO. Un `$export` conforme entrega el compartimento del paciente tal
//      cual, con su filiación. Este no. Un cliente que espere lo primero recibiría lo segundo sin
//      enterarse — y creería que el laboratorio no tiene nombres, en vez de que no los cede.
//   2. `_since`, `_until`, `_typeFilter`, `_elements`, `includeAssociatedData` y `organizeOutputBy`
//      NO se soportan, y se RECHAZAN. El estándar permite las dos conductas (rechazar o, con
//      `handling=lenient`, procesar avisando), y hay que decir cuál se toma.
//   3. Los ficheros CADUCAN, y el plazo es corto.
//
// Publicarlo es lo que la propia IG pide en «lo que tu servidor tiene que documentar»: el
// `CapabilityStatement` debe reflejar fielmente las operaciones implementadas. Reutilizar la canónica
// del estándar y hacer otra cosa sería declarar una conformidad que no se cumple.

Instance: ExportacionDeCohorte
InstanceOf: OperationDefinition
Usage: #definition
Title: "Operación: exportar una cohorte de vigilancia"
Description: """
`POST [base]/Group/{id}/$export` — exportación masiva, asíncrona y **seudonimizada** de los miembros de
una cohorte de vigilancia epidemiológica.

Sigue el patrón asíncrono de **Bulk Data Access**: `202 Accepted` con `Content-Location`, sondeo de
esa URL hasta el manifiesto, y NDJSON por tipo de recurso. Lo que **no** sigue es el contenido: ver
las divergencias en `CohorteVigilancia` y arriba.
"""

* id = "exportar-cohorte"
* url = "https://aojeda006.github.io/HispaLIS/fhir/OperationDefinition/exportar-cohorte"
* version = "0.1.0"
* name = "ExportacionDeCohorte"
* status = #active
* experimental = true
* date = "2026-08-10"
* publisher = "Andrés Ojeda Rodríguez"
* kind = #operation
* affectsState = false
* code = #export
* resource[0] = #Group
* system = false
* type = false
* instance = true

* comment = """
**Autorización.** Exige un testigo de SMART Backend Services con **los dos** ámbitos a la vez:
`system/Group.rs` —la IG de Bulk Data pide autorización sobre el propio grupo— y `system/*.rs`, que es
lo que cubre de verdad lo que el fichero se lleva. Es la misma forma de la regla de `$reconciliar`, y
por la misma razón: **ningún cliente lo tiene concedido de fábrica**. Un testigo de usuario no
exporta, por muchos permisos que traiga: una exportación masiva no es un acto asistencial.

**Nada de PHI en la URL.** Ni el trabajo ni los ficheros se nombran con datos de nadie: el sondeo va
por un identificador opaco y cada fichero se descarga con un billete de un solo significado. Una URL
acaba en el log del proxy, en el historial del navegador y en la analítica (`adr-0016`).

**Caducidad.** El manifiesto y los ficheros vienen con `Expires`. Pasado el plazo, el sondeo contesta
`404` y en el disco no queda nada; un `DELETE` sobre la URL de sondeo lo adelanta.

**Parámetros no soportados.** `_since`, `_until`, `_typeFilter`, `_elements`, `includeAssociatedData`,
`organizeOutputBy` y `allowPartialManifests` se rechazan con `400` y un `OperationOutcome` que dice
cuál. No se ignoran: ignorarlos devolvería más datos de los que el cliente pidió, sin decírselo.
"""

* parameter[0].name = #_outputFormat
* parameter[0].use = #in
* parameter[0].min = 0
* parameter[0].max = "1"
* parameter[0].type = #string
* parameter[0].documentation = "Solo NDJSON: `application/fhir+ndjson`, `application/ndjson` o `ndjson`. Cualquier otro valor es `400`."

* parameter[1].name = #_type
* parameter[1].use = #in
* parameter[1].min = 0
* parameter[1].max = "*"
* parameter[1].type = #string
* parameter[1].documentation = "Acota los tipos exportados. Se repite el parámetro; la lista con comas también se acepta, pero el estándar la señala como candidata a desaparecer. Sin él salen todos los que el servidor exporta."
