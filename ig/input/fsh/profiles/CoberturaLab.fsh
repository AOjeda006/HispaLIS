Profile: CoberturaLab
Parent: Coverage
Id: cobertura-lab
Title: "Cobertura de la petición"
Description: """
Quién paga la analítica: el propio paciente o una aseguradora o mutualidad.

Esa distinción es **la** distinción de negocio de un laboratorio privado, y R5 la fuerza a estar
presente: `kind` es `1..1` obligatorio, con `self-pay` para el privado que paga de su bolsillo e
`insurance` para la mutua o la aseguradora. Un `Coverage` de R4, que no tiene ese elemento, **no
valida** aquí.

La invariante `hlis-cob-1` cierra el círculo: una cobertura asegurada sin aseguradora es un dato
inservible para facturar, y una cobertura privada con aseguradora es una contradicción.

⚠️ **R5:** `subscriberId` pasó de ser un `string` a ser `0..* Identifier`. El número de póliza o de
mutualista va ahí, con su `system`.
"""

* obeys hlis-cob-1

* status MS

* kind 1..1 MS
* kind ^short = "`insurance` (mutua o aseguradora) | `self-pay` (paga el paciente) | `other`"

* beneficiary 1..1 MS
* beneficiary only Reference(PacienteLabES)

* insurer MS
* insurer ^short = "Aseguradora o mutualidad; ausente en `self-pay`"

* subscriber MS
* subscriber only Reference(PacienteLabES or RelatedPerson)
* subscriberId MS
* subscriberId ^short = "Número de póliza o de mutualista"

* type MS
* period MS
* relationship MS


Invariant: hlis-cob-1
Description: "Una cobertura de tipo `insurance` debe indicar la aseguradora, y una de tipo `self-pay` no puede llevarla."
Severity: #error
Expression: "(kind != 'insurance' or insurer.exists()) and (kind != 'self-pay' or insurer.empty())"
