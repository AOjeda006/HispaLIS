// Qué tienen en común los miembros de una cohorte de vigilancia.
//
// Es un CodeSystem de un solo concepto, y existir con uno solo se justifica igual: el invariante 4 del
// proyecto dice que la terminología es una caja obligatoria, no una cadena escrita a mano. Poner
// `characteristic.code.text = "enfermedad declarada"` haría que el criterio de pertenencia a la
// cohorte fuese texto libre — es decir, ilegible para cualquier máquina y distinto cada vez que
// alguien lo teclee.
//
// Y hay una segunda razón, menos obvia: `Group.characteristic` es lo que dice POR QUÉ alguien está en
// la cohorte. Un `Group` con miembros y sin rasgo es una lista de personas de la que nadie puede
// deducir el criterio, y una lista así no se puede auditar. Con el rasgo, la pregunta «¿por qué está
// este paciente aquí?» se contesta leyendo el recurso.

CodeSystem: RasgosDeCohorte
Id: rasgos-de-cohorte
Title: "Rasgos por los que se pertenece a una cohorte"
Description: """
Criterios de pertenencia a un `Group` de vigilancia epidemiológica de este laboratorio.

Hoy hay uno solo, y es el que tiene motivo legal: haber sido **declarado a Salud Pública** por una
enfermedad de declaración obligatoria. No es lo mismo que «haber dado positivo»: entre las dos cosas
está la validación facultativa y la declaración, que es lo que convierte un dato de laboratorio en un
caso de vigilancia.
"""

* ^caseSensitive = true
* ^content = #complete
* ^valueSet = Canonical(RasgosDeCohorteVs)

* #enfermedad-declarada "Enfermedad declarada a Salud Pública" "El paciente tiene al menos un resultado validado por el que el laboratorio ha abierto una declaración obligatoria de esta enfermedad."


ValueSet: RasgosDeCohorteVs
Id: rasgos-de-cohorte-vs
Title: "Rasgos de cohorte"
Description: "Todos los criterios de pertenencia a una cohorte de vigilancia de este laboratorio."
* ^experimental = true
* include codes from system RasgosDeCohorte
