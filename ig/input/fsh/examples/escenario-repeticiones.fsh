// Escenario: los otros dos valores de `triggeredBy`, que no son la prueba refleja.
//
// El elemento tiene tres códigos y los tres significan cosas distintas. Confundirlos no es un matiz
// de catalogación: el que lee el informe deduce de ellos por qué hay dos cifras de la misma prueba.
//
//   reflex  — se hizo OTRA prueba porque esta salió alterada (TSH → T4 libre; escenario principal).
//   repeat  — se repitió LA MISMA prueba con lo mismo. Aquí: la muestra estaba hemolizada.
//   re-run  — se repitió LA MISMA prueba con OTRA cosa: otro ajuste, otra calibración, otro
//             reactivo. Aquí: el control de calidad del turno se salió y hubo que recalibrar.
//
// La diferencia entre `repeat` y `re-run` está en la definición del propio código de R5 —«same
// parameters/settings/solution» frente a «different parameters/settings/solution»— y es justo la
// que distingue «la muestra estaba mal» de «el analizador estaba mal». Datos SINTÉTICOS.

Instance: especimen-repeticion
InstanceOf: EspecimenLab
Usage: #example
Title: "Sangre venosa, segunda extracción"
Description: "El tubo bueno. Se extrae porque el primero estaba hemolizado, no porque el paciente pidiera nada."
* status = #available
* accessionIdentifier.system = "https://aojeda006.github.io/HispaLIS/sid/acceso"
* accessionIdentifier.value = "26-0198512"
* type = $SCT#122555007
* subject = Reference(paciente-ejemplo)
* receivedTime = "2026-07-28T11:40:00+02:00"
* collection.collectedDateTime = "2026-07-28T11:15:00+02:00"


Instance: resultado-potasio-hemolizado
InstanceOf: ResultadoLab
Usage: #example
Title: "Potasio 6,9 mmol/L, sobre muestra hemolizada"
Description: """
La cifra que **no** se informa. Un potasio de 6,9 pasaría el umbral crítico y dispararía una llamada,
pero la hemólisis libera potasio de dentro de los hematíes y lo sube falsamente: el valor es del
tubo, no del paciente.

Se queda en `preliminary` y ahí termina. No se valida, no se informa y no se notifica — pero
**tampoco se borra**, porque existió y hay que poder explicar por qué se pinchó al paciente dos veces.
"""
* status = #preliminary
* code = CatalogoPruebas#K "Potasio"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-ejemplo)
// Que no esté validado no significa que no lo midiera nadie: lo midió el laboratorio. Sin
// `performer`, el propio validador avisa — y con razón, porque una cifra sin nadie detrás no se
// puede reclamar.
* performer[0] = Reference(laboratorio-ejemplo)
* effectiveDateTime = "2026-07-28T09:30:00+02:00"
* valueQuantity.value = 6.9
* valueQuantity.unit = "mmol/L"
* valueQuantity.system = $UCUM
* valueQuantity.code = #mmol/L
* note[0].text = "Índice de hemólisis por encima del límite de aceptación. No se informa; se solicita nueva extracción."


Instance: resultado-potasio-repetido
InstanceOf: ResultadoLab
Usage: #example
Title: "Potasio 4,3 mmol/L, repetido"
Description: """
La misma prueba, con el mismo método, sobre otro tubo: eso es `repeat`.

El `reason` no está para adornar. Sin él, quien mire la historia ve dos potasios del mismo día que se
contradicen —6,9 y 4,3— y no tiene forma de saber cuál vale ni por qué. Con él, la pregunta ya está
contestada dentro del recurso.
"""
* status = #final
* code = CatalogoPruebas#K "Potasio"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-repeticion)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-07-28T11:52:00+02:00"
* issued = "2026-07-28T13:20:00+02:00"
* valueQuantity.value = 4.3
* valueQuantity.unit = "mmol/L"
* valueQuantity.system = $UCUM
* valueQuantity.code = #mmol/L
* triggeredBy[0].observation = Reference(resultado-potasio-hemolizado)
* triggeredBy[0].type = #repeat
* triggeredBy[0].reason = "Repetido sobre una segunda extracción: la muestra anterior estaba hemolizada y eso sube el potasio falsamente."
* referenceRange[0].low.value = 3.5
* referenceRange[0].low.unit = "mmol/L"
* referenceRange[0].low.system = $UCUM
* referenceRange[0].low.code = #mmol/L
* referenceRange[0].high.value = 5.1
* referenceRange[0].high.unit = "mmol/L"
* referenceRange[0].high.system = $UCUM
* referenceRange[0].high.code = #mmol/L


Instance: resultado-sodio-control-fuera
InstanceOf: ResultadoLab
Usage: #example
Title: "Sodio 149 mmol/L, con el control fuera"
Description: """
Medido con el analizador descalibrado. La muestra está bien y el paciente está bien: lo que falla es
el aparato, y se sabe porque el control de calidad interno de ese turno se salió de sus límites.

Como en la hemólisis, se queda en `preliminary` y no se borra.
"""
* status = #preliminary
* code = CatalogoPruebas#NA "Sodio"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-ejemplo)
* performer[0] = Reference(laboratorio-ejemplo)
* effectiveDateTime = "2026-07-28T09:31:00+02:00"
* valueQuantity.value = 149
* valueQuantity.unit = "mmol/L"
* valueQuantity.system = $UCUM
* valueQuantity.code = #mmol/L
* note[0].text = "Control de calidad interno del turno fuera de límites en el canal de sodio. No se informa."


Instance: resultado-sodio-reejecutado
InstanceOf: ResultadoLab
Usage: #example
Title: "Sodio 141 mmol/L, re-ejecutado"
Description: """
La misma prueba y **la misma muestra**, pero con el analizador recalibrado: eso es `re-run`, y es lo
que lo distingue de `repeat`. En la repetición cambia el tubo; en la re-ejecución cambia el ajuste
con el que se mide.

Que sean dos códigos y no uno importa para el que audita: `repeat` apunta a la fase preanalítica —el
tubo, la extracción, el transporte— y `re-run` apunta al analizador. Contarlos juntos taparía cuál de
los dos procesos es el que se está yendo.
"""
* status = #final
* code = CatalogoPruebas#NA "Sodio"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-ejemplo)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-07-28T12:10:00+02:00"
* issued = "2026-07-28T13:20:00+02:00"
* valueQuantity.value = 141
* valueQuantity.unit = "mmol/L"
* valueQuantity.system = $UCUM
* valueQuantity.code = #mmol/L
* triggeredBy[0].observation = Reference(resultado-sodio-control-fuera)
* triggeredBy[0].type = #re-run
* triggeredBy[0].reason = "Re-ejecutado tras recalibrar el analizador: el control de calidad interno del turno estaba fuera de límites."
* referenceRange[0].low.value = 135
* referenceRange[0].low.unit = "mmol/L"
* referenceRange[0].low.system = $UCUM
* referenceRange[0].low.code = #mmol/L
* referenceRange[0].high.value = 145
* referenceRange[0].high.unit = "mmol/L"
* referenceRange[0].high.system = $UCUM
* referenceRange[0].high.code = #mmol/L
