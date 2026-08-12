Profile: CohorteVigilancia
Parent: Group
Id: cohorte-vigilancia
Title: "Cohorte de vigilancia epidemiológica"
Description: """
El conjunto de pacientes que el laboratorio ha declarado a Salud Pública por una misma enfermedad de
declaración obligatoria. Es lo único que este servidor exporta con `$export`.

**No la compone un cliente.** La abre y la mantiene el laboratorio: cuando se declara una enfermedad
(`NotificacionEDO`), el sujeto de ese resultado entra en la cohorte de esa enfermedad. Un `POST` de
fuera creando o modificando un `Group` se rechaza, y la razón es la que hace que esta guía tenga
sentido: si el cliente compone la lista, exporta a quien quiera y la exportación deja de tener motivo.

La IG de **Bulk Data Access** describe tres patrones de gestión de grupos —de solo lectura, por
miembros y por criterios— y dice que el servidor debe elegir uno y documentarlo. Este es el
**primero**: los gestiona el servidor. La *Bulk Cohort API*, que sería el tercero, es experimental
(FMM 1) dentro de una IG FMM 5, y este proyecto no construye sobre lo experimental.

**El rasgo y los miembros están los dos, y hacen falta los dos.** `characteristic` dice *por qué* se
pertenece; `member` dice *quién* pertenece hoy. Con solo los miembros, la cohorte es una lista de
personas de la que nadie puede deducir el criterio — y una lista así no se puede auditar ni
reconstruir.

⚠️ **R5 no es R4 aquí.** El booleano `Group.actual` **ya no existe**: lo sustituye
`Group.membership` (`definitional | conceptual | enumerated`). Un `Group` de R4 con `actual = true` no
valida en R5. Y `Group.description` pasó de `string` a `markdown`.
"""

// `person` y no `practitioner` ni `specimen`: la cohorte epidemiológica es de personas. El tipo se
// fija porque de él depende qué compartimento se exporta.
* type 1..1 MS
* type = #person (exactly)

// ⚠️ R5: `membership`, no el `actual` de R4. `enumerated` porque los miembros están uno a uno: el
// laboratorio sabe exactamente a quién ha declarado, y una cohorte `definitional` —«todos los que
// cumplan X»— dejaría el criterio a interpretación de quien la resuelva.
* membership 1..1 MS
* membership = #enumerated (exactly)
* membership ^short = "`enumerated`: los casos están enumerados, no definidos por un criterio a resolver"

* identifier 1..1 MS
* identifier ^short = "Identificador estable de la cohorte, con el código de la enfermedad como valor"

* name 1..1 MS
* name ^short = "Nombre legible: «Casos declarados de …»"

* active 1..1 MS

// QUIÉN RESPONDE DE LA COHORTE. No es decorativo: en una cesión de datos, quién la mantiene es parte
// de lo que hay que poder acreditar.
* managingEntity 1..1 MS
* managingEntity only Reference(LaboratorioOrg)

// EL RASGO — por qué se está aquí.
* characteristic 1..1 MS
* characteristic.code 1..1 MS
* characteristic.code from RasgosDeCohorteVs (required)
* characteristic.value[x] only CodeableConcept
* characteristic.valueCodeableConcept 1..1 MS
* characteristic.valueCodeableConcept from EnfermedadesDeclarables (required)
* characteristic.valueCodeableConcept ^short = "La enfermedad declarada que define esta cohorte"
* characteristic.exclude 1..1 MS
* characteristic.exclude = false (exactly)

// LOS MIEMBROS. Solo pacientes de este laboratorio: un `Group` de vigilancia con un `Practitioner`
// dentro exportaría el compartimento de un profesional, que no es de lo que va esto.
* member MS
* member.entity only Reference(PacienteLabES)
* member.entity ^short = "Un paciente con al menos una declaración de esta enfermedad"
* member.period MS
* member.period ^short = "Desde cuándo es caso. El fin, si lo hay, es cuando dejó de contar para la vigilancia"
* member.inactive MS

* quantity MS
* quantity ^short = "Cuántos casos, sin tener que contar los miembros"
