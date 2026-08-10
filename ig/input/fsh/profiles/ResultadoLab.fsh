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
* code from PruebasDelCatalogo (extensible)
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

// El cualitativo va CODIFICADO y no en texto, y no es una preferencia de estilo: de este valor
// depende que se declare o no una enfermedad a Salud Pública. Con «Positivo» en texto libre, la
// regla sería una comparación de cadenas. El enlace es extensible porque un grupo sanguíneo o un
// serotipo también son resultados codificados y no caben en el conjunto.
// El `MS` no es decorativo: `value[x]` lo es, y una rebanada suya sin marcar hace que el publisher
// avise de que la rebanada contradice al elemento que la define.
* valueCodeableConcept MS
* valueCodeableConcept from ResultadoCualitativo (extensible)
* valueCodeableConcept ^short = "Resultado de una prueba cualitativa: positivo, negativo o indeterminado"
* valueQuantity MS
* valueQuantity.value 1..1
* valueQuantity.unit 1..1 MS
* valueQuantity.unit ^short = "Unidad tal como se imprime en el informe"
* valueQuantity.system 1..1
* valueQuantity.system = $UCUM
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

// Los tres códigos se admiten, pero NO los declara el mismo: `reflex` lo decide el laboratorio a
// partir de la regla del catálogo (`prueba-refleja`) y lo rechaza si llega de fuera —quien manda un
// resultado no decide el protocolo del laboratorio—; `repeat` y `re-run` los declara quien repite,
// porque la hemólisis del tubo y el control de calidad del turno solo los ve él.
* triggeredBy MS
* triggeredBy.observation only Reference(ResultadoLab)
* triggeredBy.type MS
* triggeredBy.type ^short = "`reflex` | `repeat` | `re-run`"
// El `reason` es la mitad que se olvida, y sin él el elemento no sirve de nada a quien lee: dos
// cifras de la misma prueba con un enlace entre ellas siguen sin explicar cuál vale y por qué.
* triggeredBy.reason MS
* triggeredBy.reason ^short = "Por qué existe esta determinación, en español y en una frase"

* hasMember MS
* hasMember only Reference(ResultadoLab)
* note MS
