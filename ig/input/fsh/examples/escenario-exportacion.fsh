// Escenario: la cohorte de vigilancia y su exportación masiva.
//
// El eslabón que faltaba entre el ítem 48 y Bulk Data. Una declaración EDO no acaba en el `Task`: el
// caso entra en una cohorte, y esa cohorte es lo que una unidad de vigilancia epidemiológica pide
// entera cuando investiga un brote — no persona a persona, sino de una vez.
//
// Los tres recursos de aquí son un `Group` mantenido por el laboratorio, y nada más. El resultado de
// la exportación NO es un recurso FHIR: es un manifiesto JSON y unos ficheros NDJSON, que por
// definición no se publican en una guía. Lo que se documenta aquí es la cohorte, que es lo que sí es
// un recurso, y en su descripción, cómo se pide.
//
// Datos SINTÉTICOS.

Instance: cohorte-legionelosis
InstanceOf: CohorteVigilancia
Usage: #example
Title: "Cohorte de vigilancia: casos declarados de legionelosis"
Description: """
Los pacientes que este laboratorio ha declarado a Salud Pública por legionelosis. Es lo que se exporta,
y es lo único que se exporta.

**El identificador es el código de la enfermedad**, y así es como un cliente descubre la cohorte sin
tener que saberse los ids: `GET [base]/Group?identifier=…|LEGIONELOSIS`. La IG de Bulk Data lo pide
expresamente — si soportas exportación de grupo, soporta también leer y buscar `Group`—, y la razón es
que un cliente no debe depender de identificadores técnicos que el servidor puede cambiar.

## Cómo se exporta

```
POST [base]/Group/cohorte-legionelosis/$export
Accept: application/fhir+json
Prefer: respond-async
```

Contesta `202 Accepted` con `Content-Location`, que es la URL de sondeo y el identificador del
trabajo. Se sondea hasta que devuelve `200` con el manifiesto, y del manifiesto salen los NDJSON.
`DELETE` sobre esa misma URL cancela y borra los ficheros.

## Y lo que hay que saber antes de usarlo

- **El testigo tiene que traer `system/Group.rs` y `system/*.rs`.** Los dos, y de un cliente de
  sistema. Ninguno del *realm* los tiene concedido de fábrica.
- **Lo que sale va seudonimizado.** El `Patient` del NDJSON no lleva nombre, ni documento, ni NUHSA,
  ni NHC: lleva sexo, año de nacimiento y municipio. Es una **divergencia consciente** de un servidor
  Bulk Data conforme, que sacaría el compartimento tal cual.
- **`_since` y `_typeFilter` no se soportan, y se rechazan con `400`** en vez de ignorarse. Ignorar un
  parámetro devolvería más datos de los que el cliente pidió sin decírselo, que es lo contrario de lo
  que un cliente de Bulk Data espera.
- **Los ficheros caducan y se borran.** Vienen con `Expires`; pasado el plazo hay que volver a pedir el
  manifiesto, y ya no habrá nada. Un NDJSON con una cohorte entera olvidado en un disco es el peor
  activo de todo el montaje.
"""
* identifier[0].system = "https://aojeda006.github.io/HispaLIS/sid/cohorte-vigilancia"
* identifier[0].value = "LEGIONELOSIS"
* active = true
* type = #person
// ⚠️ R5: `membership`, no el `actual` booleano de R4.
* membership = #enumerated
* name = "Casos declarados de legionelosis"
* description = "Pacientes con al menos un resultado validado por el que se ha abierto declaración obligatoria de legionelosis."
* managingEntity = Reference(laboratorio-ejemplo)
* characteristic[0].code = RasgosDeCohorte#enfermedad-declarada "Enfermedad declarada a Salud Pública"
* characteristic[0].valueCodeableConcept = EnfermedadesEdo#LEGIONELOSIS "Legionelosis"
* characteristic[0].exclude = false
* quantity = 1
* member[0].entity = Reference(paciente-ejemplo)
* member[0].period.start = "2026-08-03T19:41:00+02:00"
