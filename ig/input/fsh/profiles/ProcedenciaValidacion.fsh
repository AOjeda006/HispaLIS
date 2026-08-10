Profile: ProcedenciaValidacion
Parent: Provenance
Id: procedencia-validacion
Title: "Procedencia de una validación facultativa"
Description: """
La constancia de **quién firmó un resultado y cuándo**. Es lo que convierte un `ResultadoLab` en
`final`: sin una procedencia detrás, el resultado no está validado y el laboratorio no responde de
él.

**Por qué un `Provenance` y no un campo de `Observation`.** §6.1 del diseño lo verificó contra el
paquete canónico: FHIR tiene recurso para esto. Un `Observation` dice *qué* se midió; quién responde
de que esa medida sea publicable es un hecho **sobre** el recurso, no un campo suyo. Meterlo dentro
obligaría a versionar el resultado cada vez que cambiase el rastro, y mezclaría el dato clínico con
su auditoría.

**Por qué este perfil existe, siendo tan corto.** No añade cardinalidades por gusto: dice las tres
cosas que un cliente no puede deducir del recurso base.

1. Que el `agent.type` de este laboratorio es siempre `verifier`. El recurso base admite `author`,
   `enterer`, `performer`, `custodian`… Un cliente que lea `Provenance` sin saberlo tiene que
   descubrir por ensayo que aquí solo significa «validó».
2. Que apunta a **un** `ResultadoLab` y no a cualquier cosa. `Provenance.target` es `0..*` de
   `Reference(Any)`; aquí cada procedencia da fe de **un** acto sobre **una** cifra, y no de un lote.
3. Que `recorded` está **siempre**. En el recurso base es `0..1`, y una firma sin fecha no es una
   firma.

**Un resultado puede tener más de una.** La relación es uno a uno en esta dirección —una procedencia,
un resultado— pero no en la contraria: un valor **crítico** exige dos firmas de facultativos
distintos, y cada una deja la suya. Fundirlas en una sola con dos agentes diría que las dos personas
firmaron a la vez lo mismo, cuando lo que ocurrió fue una revisión y después una contra-revisión, en
momentos distintos y con responsabilidades separables.

**Se lee, no se escribe.** El laboratorio genera la procedencia dentro de la misma transacción que
la validación, y rechaza un `POST` o un `PUT` contra `Provenance`: un cliente que pudiera crearla
estaría certificando una validación que no ha ocurrido. Se consulta con
`POST Provenance/_search` y `target=Observation/{id}`.
"""

// Un acto, una cifra. Que el `target` sea `1..1` y no `1..*` es la forma procesable de decir que
// aquí no se firman lotes. Al revés sí caben varias: un crítico tiene dos procedencias.
* target 1..1 MS
* target only Reference(ResultadoLab)
* target ^short = "El resultado que se firma"

// Una firma sin fecha no es una firma.
* recorded 1..1 MS
* recorded ^short = "Cuándo se firmó"

* agent 1..1 MS
* agent.type 1..1 MS
* agent.type = $PARTICIPANTE_PROCEDENCIA#verifier
* agent.type ^short = "Siempre `verifier`: este laboratorio solo publica procedencia de validación"
* agent.who 1..1 MS
* agent.who only Reference(FacultativoLab)
* agent.who ^short = "El facultativo que responde del resultado"
