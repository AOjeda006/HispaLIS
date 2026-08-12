// =============================================================================
// Alias de HispaLIS — tabla única de `system` y de sistemas de terminología.
//
// FUENTE DE VERDAD de los `Identifier.system` del proyecto (§4.8 del diseño, D19) y resultado del
// ítem 0 del checklist. Todo perfil, ValueSet y ejemplo referencia estos alias: no se escribe una
// URI a mano en ningún `.fsh`, para que cambiar un `system` sea cambiar una línea de este fichero.
//
// Dos procedencias, deliberadamente distintas y documentadas como tales en la IG:
//
//   ADOPTADO  — lo publica el Ministerio de Sanidad y lo usamos tal cual.
//   PROPIO    — no existe URI oficial española; la define este proyecto bajo su base canónica y la
//               IG advierte de que es propia, no oficial.
// =============================================================================


// ─── Terminología estándar ───────────────────────────────────────────────────

Alias: $SCT   = http://snomed.info/sct
Alias: $LOINC = http://loinc.org
Alias: $UCUM  = http://unitsofmeasure.org

// SNOMED CT Edición Española + extensión nacional del SNS (D7). Los `display` de un informe español
// van en español: la edición internacional los daría en inglés, que es un error de producto.
Alias: $SCT_ES = http://snomed.info/sct/900000001000122104

// Refset «Tipos de documento para identificación personal» de la extensión española del SNS.
// Es el `system` con el que ÚNICAS codifica `Identifier.type` del DNI, el pasaporte y el NIE.
Alias: $SCT_ES_REFSET_DOCUMENTOS = http://snomed.info/sct/900000001000122104?fhir_vs-refset/900000251000122107

Alias: $TIPOS_IDENTIFICADOR_HL7 = http://terminology.hl7.org/CodeSystem/v2-0203

// Motivos por los que se rechaza o se describe una muestra. Se usan los dos porque ninguno cubre
// solo lo necesario: ver la descripción de `ValueSet/motivos-rechazo-muestra`.
Alias: $CRITERIOS_RECHAZO      = http://terminology.hl7.org/CodeSystem/rejection-criteria
Alias: $CONDICION_MUESTRA_HL7  = http://terminology.hl7.org/CodeSystem/v2-0493

// Estado de ayuno del paciente: `Specimen.collection.fastingStatus` lo ata con fuerza required.
Alias: $AYUNO_HL7              = http://terminology.hl7.org/CodeSystem/v2-0916

// Qué papel jugó un agente en un acto registrado. El laboratorio solo emite `verifier`: la
// procedencia que publica da fe de UNA cosa, que un facultativo firmó un resultado.
Alias: $PARTICIPANTE_PROCEDENCIA = http://terminology.hl7.org/CodeSystem/provenance-participant-type

// Qué clase de tarea es un `Task`. La declaración EDO usa `fulfill`: cumplir con lo que otra cosa
// —aquí, la ley— exige a partir de un resultado ya emitido.
Alias: $TIPOS_DE_TAREA = http://hl7.org/fhir/CodeSystem/task-code

// La traza de acceso (`AuditEvent`) y sus cuatro vocabularios estándar. ⚠️ En R5 son otros elementos
// que en R4: `category`/`code` sustituyen a `type`/`subtype`, y el desenlace pasó de ser un código
// suelto a un elemento con su `Coding` dentro. Detalle en el perfil `TrazaDeAcceso`.
Alias: $TIPOS_DE_TRAZA     = http://terminology.hl7.org/CodeSystem/audit-event-type
Alias: $INTERACCION_REST   = http://hl7.org/fhir/restful-interaction
Alias: $DESENLACE_DE_TRAZA = http://terminology.hl7.org/CodeSystem/audit-event-outcome
Alias: $PAPEL_DE_AGENTE    = http://terminology.hl7.org/CodeSystem/extra-security-role-type
Alias: $PAPEL_DE_OBJETO    = http://terminology.hl7.org/CodeSystem/object-role

// Interpretación de un resultado (`Observation.interpretation`). En una prueba cualitativa dice si el
// hallazgo es el buscado, que NO es lo mismo que el valor: `POS` interpreta, `ResultadosCualitativos#POS`
// informa. El primero es del vocabulario común; el segundo, del dialecto del laboratorio.
Alias: $INTERPRETACION = http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation


// ─── `Identifier.system` — ADOPTADOS de la IG española de ÚNICAS ─────────────
//
// ÚNICAS (Ministerio de Sanidad, FHIR R5, v0.0.11) no publica `NamingSystem` ni fija estos `system`
// con `pattern` en su perfil `UNICASPatient`; declara `Patient.identifier.system` como `1..1` con la
// descripción «OID registro según el tipo de documento de identificación» y usa estos dos OID de
// forma consistente en todos sus ejemplos y en el `example` del propio elemento.
//
// Se adoptan igualmente: son los OID del registro español que usa la autoridad nacional, adoptarlos
// no cuesta nada, y lo contrario sería que HispaLIS inventase para el DNI una URI que contradice al
// Ministerio. Verificado sobre `package.tgz`, no sobre el sitio renderizado.

Alias: $SID_DNI_NIE = urn:oid:1.3.6.1.4.1.19126.3
Alias: $SID_CIP_SNS = urn:oid:2.16.724.4.40


// ─── `Identifier.system` — PROPIOS de HispaLIS (D19, §4.8) ───────────────────
//
// ÚNICAS no define URI para ninguno de estos. En particular NO define el OID del CIP autonómico de
// Andalucía: la rama `2.16.724.4.21.5.*` de su paquete es de catálogos clínicos (formas
// farmacéuticas, diagnósticos, procedimientos), no de identificadores de paciente.

Alias: $SID_NHC       = https://aojeda006.github.io/HispaLIS/sid/nhc
Alias: $SID_NUHSA     = https://aojeda006.github.io/HispaLIS/sid/nuhsa
Alias: $SID_NASS      = https://aojeda006.github.io/HispaLIS/sid/nass
Alias: $SID_NICA      = https://aojeda006.github.io/HispaLIS/sid/nica
Alias: $SID_NIF       = https://aojeda006.github.io/HispaLIS/sid/nif
Alias: $SID_COLEGIADO = https://aojeda006.github.io/HispaLIS/sid/colegiado

// El `system` del colegiado lleva el colegio emisor al final, así que hay uno por colegio. Se
// declaran aquí los que usan los ejemplos, para que tampoco esos escriban la URI a mano.
Alias: $SID_COLEGIADO_COM_SEVILLA = https://aojeda006.github.io/HispaLIS/sid/colegiado/com-sevilla


// ─── Extensiones estándar (paquete aparte en R5: `hl7.fhir.uv.extensions`) ───
//
// ⚠️ Las dos de apellidos se declaran sobre el elemento `HumanName.family`, NO sobre `HumanName`.
// Declararlas en el sitio equivocado hace que la IG no compile (§4.2, `ig/CLAUDE.md`).

Alias: $EXT_APELLIDO_PADRE = http://hl7.org/fhir/StructureDefinition/humanname-fathers-family
Alias: $EXT_APELLIDO_MADRE = http://hl7.org/fhir/StructureDefinition/humanname-mothers-family

// Ausencia como información, nunca un valor vacío. En `Observation` es elemento del núcleo; para el
// resto de elementos (un DNI que no consta, por ejemplo) hace falta la extensión.
Alias: $EXT_AUSENCIA_DATO = http://hl7.org/fhir/StructureDefinition/data-absent-reason


// ─── La única extensión propia del proyecto (D9, §6.4) ───────────────────────
//
// Código INE de municipio y provincia sobre `Address`: no existe elemento ni extensión estándar.
// Cualquier extensión propia ADICIONAL debe justificarse por escrito contra la tabla de §6.1 antes
// de crearse.

Alias: $EXT_CODIGO_INE = https://aojeda006.github.io/HispaLIS/fhir/StructureDefinition/codigo-ine
