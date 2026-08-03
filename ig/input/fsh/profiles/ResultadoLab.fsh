Profile: ResultadoLab
Parent: Observation
Id: resultado-lab
Title: "Resultado de laboratorio"
Description: """
Resultado de una determinación analítica sobre un espécimen concreto.

El **espécimen es obligatorio**: un resultado de laboratorio sin muestra de la que provenga no es
trazable, y la trazabilidad muestra → resultado es lo que sostiene todo lo demás.

Un valor numérico va **siempre** con unidad UCUM. Presentar una cifra sin unidad y sin rango de
referencia es un error de producto: `4,2` no significa nada por sí solo.

⚠️ **R5:** `triggeredBy` es nuevo y es el gancho de las **pruebas reflejas** — la refleja
propiamente dicha (una TSH alterada dispara una T4 libre), la repetición de control y la
re-ejecución por fallo técnico. En R4 esto había que inventarlo.
"""

* status MS
* category MS

* code MS
* code ^short = "Magnitud medida, del catálogo del laboratorio (mapeado a LOINC)"

* subject 1..1 MS
* subject only Reference(PacienteLabES)

* specimen 1..1 MS
* specimen only Reference(EspecimenLab)

* basedOn MS
* basedOn only Reference(PeticionLab)

* performer MS
* performer only Reference(FacultativoLab or PractitionerRole or LaboratorioOrg)

* effective[x] MS
* issued MS

// Un laboratorio emite tres formas de resultado: cuantitativo con unidad, cualitativo codificado
// (positivo/negativo, grupo sanguíneo) y textual para lo que no se deja codificar. El resto de los
// tipos de `value[x]` no corresponden a este dominio.
* value[x] only Quantity or CodeableConcept or string
* value[x] MS
* valueQuantity.value 1..1
* valueQuantity.unit 1..1 MS
* valueQuantity.unit ^short = "Unidad tal como se imprime en el informe"
* valueQuantity.system 1..1
* valueQuantity.system = $UCUM (exactly)
* valueQuantity.code 1..1 MS
* valueQuantity.code ^short = "Código UCUM de la unidad, que es el que permite convertir y comparar"

// La ausencia de valor es información y se declara, nunca se deja el resultado vacío.
* dataAbsentReason MS
* interpretation MS

* referenceRange MS
* referenceRange.low MS
* referenceRange.high MS
// Rango por sexo y por edad sin extensión (§6.1): `appliesTo` recoge la población a la que aplica y
// `age` el tramo de edad.
* referenceRange.appliesTo MS
* referenceRange.age MS

* triggeredBy MS
* triggeredBy.observation only Reference(ResultadoLab)
* triggeredBy.type MS
* triggeredBy.type ^short = "`reflex` | `repeat` | `re-run`"

* hasMember MS
* hasMember only Reference(ResultadoLab)
* note MS
