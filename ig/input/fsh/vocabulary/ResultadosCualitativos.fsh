// Los valores con los que este laboratorio informa una prueba cualitativa.
//
// Hacen falta porque el criterio de una declaración obligatoria se decide SOBRE CÓDIGOS. Con el
// resultado en texto libre —«Positivo», «POSITIVO», «Se detecta»— la regla sería una comparación de
// cadenas, y una comparación de cadenas que decide si se declara una legionelosis a Salud Pública no
// es una regla: es una apuesta.
//
// Es un CodeSystem PROPIO y no SNOMED (`10828004 |Positive|`, `260385009 |Negative|`) por la misma
// razón que el catálogo de pruebas: es el dialecto local con el que el personal informa, y la
// traducción al lenguaje común es trabajo del mapeo, no del dato de origen. Cuando la edición
// española de SNOMED esté disponible (ítem 42), el `ConceptMap` que los enlace es una línea.

CodeSystem: ResultadosCualitativos
Id: resultados-cualitativos
Title: "Resultados cualitativos del laboratorio"
Description: """
Valores con los que se informa una prueba que no da cifra: una detección de antígeno, un cultivo, una
serología.

**Tres y no dos.** `IND` no es un adorno defensivo: una serología puede quedarse en zona gris, y
obligar a elegir entre positivo y negativo convertiría una duda del laboratorio en una afirmación.
Que sea un código propio y no la ausencia de valor es deliberado — «no concluyente» es un resultado,
y `dataAbsentReason` diría otra cosa: que no se midió.
"""

* ^caseSensitive = true
* ^content = #complete
* ^valueSet = Canonical(ResultadoCualitativo)

* #POS "Positivo" "Se detecta el analito buscado."
* #NEG "Negativo" "No se detecta el analito buscado con el límite de detección de la técnica empleada."
* #IND "Indeterminado" "El resultado no permite afirmar ni descartar. Se recomienda repetir sobre una nueva muestra."


ValueSet: ResultadoCualitativo
Id: resultado-cualitativo
Title: "Resultado cualitativo"
Description: """
Los valores admitidos en `Observation.value[x]` cuando la prueba es cualitativa.

El enlace es **extensible** y no `required`, y no por prudencia: un grupo sanguíneo o un serotipo
también son resultados codificados y no caben aquí. Cerrarlo obligaría a meterlos a la fuerza o a
informarlos como texto, que es peor.
"""

* include codes from system ResultadosCualitativos
