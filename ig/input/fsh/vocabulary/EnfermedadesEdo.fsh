// Las enfermedades de declaración obligatoria que este laboratorio puede llegar a detectar.
//
// ⚠️ VEROSÍMIL, NO FIEL, y está escrito también en la descripción del recurso porque es lo que un
// lector necesita saber antes de usarlo. Lo REAL es la obligación: todos los centros sanitarios de
// Andalucía, públicos *y privados*, forman parte del SVEA (Decreto 66/1996), y la relación de EDO la
// fija la Orden de 19 de diciembre de 1996, actualizada por la de 12 de noviembre de 2015. Lo
// SIMULADO es esta lista —la real es mucho más amplia— y el destinatario.
//
// Es un CodeSystem propio y no CIE-10-ES, que es lo que el proyecto usa para diagnósticos (§4.3), por
// una razón concreta: una EDO no es un diagnóstico del paciente. Es una entrada de una lista
// administrativa, y la RENAVE la publica con su propia nomenclatura y sus propios criterios de caso.
// Codificarla con el CIE-10 del cuadro clínico afirmaría un diagnóstico que el laboratorio no ha
// hecho: lo que el laboratorio sabe es que una muestra dio positivo, no que la persona esté enferma.

CodeSystem: EnfermedadesEdo
Id: enfermedades-edo
Title: "Enfermedades de declaración obligatoria"
Description: """
Enfermedades que obligan a declarar a Salud Pública cuando el laboratorio confirma un resultado
positivo.

**Esta relación es una simulación verosímil, no la relación oficial.** La real la fija la normativa
andaluza citada arriba y es mucho más amplia; aquí hay una muestra suficiente para que la regla sea
demostrable de extremo a extremo. El destinatario de la declaración —Redalerta— también se modela de
forma verosímil: su contrato no es público.

Qué prueba declara cada una y con qué resultado vive en `CodeSystem/catalogo-pruebas`, en las
propiedades `enfermedad-edo` y `resultado-que-declara` de cada concepto. Aquí solo están las
enfermedades: mezclar las dos cosas en un sitio sería confundir el catálogo de lo que se oferta con
el de lo que se declara.

**El plazo, en cambio, sí vive aquí**, y es deliberado: una legionelosis es urgente la detecte la
prueba que la detecte. Colgar el plazo de la prueba obligaría a repetirlo en cada técnica que
confirme la misma enfermedad, y el día que dos discrepasen no habría forma de saber cuál manda.
"""

* ^caseSensitive = true
* ^content = #complete
* ^valueSet = Canonical(EnfermedadesDeclarables)

// Las dos van juntas o no van, que es la misma regla que en `catalogo-pruebas`: una modalidad sin
// plazo no se puede vigilar y un plazo sin modalidad no se puede explicar a quien pregunta por qué
// hay prisa. `Coding` y no `code` porque la modalidad NO es un concepto de este CodeSystem —
// `URGENTE` no es una enfermedad—; es el mismo motivo por el que `unidad-ucum` es `Coding`.
* ^property[0].code = #modalidad-declaracion
* ^property[0].description = "Si la declaración es urgente u ordinaria, según la normativa andaluza. Obligatoria en todo concepto de esta lista."
* ^property[0].type = #Coding
* ^property[1].code = #plazo-horas
* ^property[1].description = "Horas de las que dispone el laboratorio para declarar, contadas desde que el resultado queda validado. Es el plazo modelado, no el literal de la norma: ver la advertencia del recurso."
* ^property[1].type = #integer

* #LEGIONELOSIS "Legionelosis" "Infección por Legionella pneumophila. Declaración urgente: un caso aislado puede ser la punta de un brote de origen ambiental."
* #LEGIONELOSIS ^property[0].code = #modalidad-declaracion
* #LEGIONELOSIS ^property[0].valueCoding = ModalidadesDeclaracionEdo#URGENTE
* #LEGIONELOSIS ^property[1].code = #plazo-horas
* #LEGIONELOSIS ^property[1].valueInteger = 24

* #SALMONELOSIS "Salmonelosis" "Infección por Salmonella no tifoidea."
* #SALMONELOSIS ^property[0].code = #modalidad-declaracion
* #SALMONELOSIS ^property[0].valueCoding = ModalidadesDeclaracionEdo#ORDINARIA
* #SALMONELOSIS ^property[1].code = #plazo-horas
* #SALMONELOSIS ^property[1].valueInteger = 168

* #TUBERCULOSIS "Tuberculosis" "Enfermedad por el complejo Mycobacterium tuberculosis."
* #TUBERCULOSIS ^property[0].code = #modalidad-declaracion
* #TUBERCULOSIS ^property[0].valueCoding = ModalidadesDeclaracionEdo#ORDINARIA
* #TUBERCULOSIS ^property[1].code = #plazo-horas
* #TUBERCULOSIS ^property[1].valueInteger = 168

* #HEPATITIS-A "Hepatitis A" "Infección aguda por el virus de la hepatitis A."
* #HEPATITIS-A ^property[0].code = #modalidad-declaracion
* #HEPATITIS-A ^property[0].valueCoding = ModalidadesDeclaracionEdo#URGENTE
* #HEPATITIS-A ^property[1].code = #plazo-horas
* #HEPATITIS-A ^property[1].valueInteger = 24

* #SARAMPION "Sarampión" "Infección por el virus del sarampión. En un país con eliminación declarada, un solo caso obliga a investigar."
* #SARAMPION ^property[0].code = #modalidad-declaracion
* #SARAMPION ^property[0].valueCoding = ModalidadesDeclaracionEdo#URGENTE
* #SARAMPION ^property[1].code = #plazo-horas
* #SARAMPION ^property[1].valueInteger = 24


// Cómo de urgente es declarar, que en la normativa no es un adjetivo sino dos regímenes distintos.
CodeSystem: ModalidadesDeclaracionEdo
Id: modalidades-declaracion-edo
Title: "Modalidades de declaración obligatoria"
Description: """
Los dos regímenes con los que se declara una EDO en Andalucía.

⚠️ **El plazo en horas que acompaña a cada enfermedad es una simplificación declarada.** La norma no
habla de horas para la declaración ordinaria: habla de la **semana epidemiológica**, que termina el
domingo, de modo que el plazo real de un caso confirmado en lunes y el de uno confirmado en sábado no
son el mismo número. Modelarlo como una ventana fija de 168 horas mantiene la propiedad que importa
—que el plazo existe, se cuenta y se puede incumplir— sin fingir un calendario epidemiológico que
este sistema no implementa. La urgente sí se corresponde con lo que dice la norma: sin demora, y como
mucho en 24 horas.
"""

* ^caseSensitive = true
* ^content = #complete
* ^valueSet = Canonical(ModalidadesDeDeclaracion)

* #URGENTE "Declaración urgente" "Se declara sin esperar, en cuanto el laboratorio confirma. Son las enfermedades en las que un solo caso puede obligar a intervenir sobre un foco."
* #ORDINARIA "Declaración ordinaria" "Se declara dentro de la semana epidemiológica. La vigilancia es de tendencia, no de alerta."


ValueSet: ModalidadesDeDeclaracion
Id: modalidades-de-declaracion
Title: "Modalidades de declaración"
Description: "Las dos de `CodeSystem/modalidades-declaracion-edo`."

* include codes from system ModalidadesDeclaracionEdo


ValueSet: EnfermedadesDeclarables
Id: enfermedades-declarables
Title: "Enfermedades declarables a Salud Pública"
Description: "Todas las de `CodeSystem/enfermedades-edo`. Es el conjunto al que apunta `NotificacionEDO`."

* include codes from system EnfermedadesEdo
