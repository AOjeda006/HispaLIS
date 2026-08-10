// Escenario: la detección de una enfermedad de declaración obligatoria.
//
// Dos resultados de la MISMA prueba, sobre dos pacientes distintos, que se diferencian en un código:
// uno declara y el otro no. Es lo que hace demostrable que la regla mira el resultado y no la prueba.
//
// La decisión se toma sobre CÓDIGOS —el de la prueba y el del valor— y en ningún momento hace falta
// saber quién es el paciente. No es un detalle de implementación: quien decide si algo se declara no
// tiene por qué poder mirar la filiación de nadie, y una regla que necesitase mirarla estaría mal
// planteada antes de estar mal escrita. Datos SINTÉTICOS.

Instance: especimen-orina-legionella
InstanceOf: EspecimenLab
Usage: #example
Title: "Orina para antígeno de Legionella"
Description: "Muestra de orina recogida en urgencias de una clínica privada por una neumonía adquirida en la comunidad."
* status = #available
* accessionIdentifier.system = "https://aojeda006.github.io/HispaLIS/sid/acceso"
* accessionIdentifier.value = "26-0203118"
* type = $SCT#122575003
* subject = Reference(paciente-ejemplo)
* receivedTime = "2026-08-03T18:20:00+02:00"
* collection.collectedDateTime = "2026-08-03T17:55:00+02:00"


Instance: resultado-legionella-positivo
InstanceOf: ResultadoLab
Usage: #example
Title: "Antígeno de Legionella: POSITIVO — declarable"
Description: """
El que **sí** declara.

Fíjate en dónde está la información que dispara la declaración: en `code` y en `valueCodeableConcept`,
los dos codificados. El laboratorio compara el código de la prueba con la propiedad `enfermedad-edo`
de su catálogo y el del valor con `resultado-que-declara`, y con eso ya sabe que hay que notificar
una legionelosis. No ha mirado el nombre del paciente, ni su edad, ni su NUHSA.

Y **está validado**, que es la otra mitad: se declara lo que el laboratorio da por definitivo, no lo
que salió del analizador. Declarar sobre un preliminar pondría en marcha una investigación
epidemiológica —en la legionelosis, la búsqueda de una torre de refrigeración— a partir de una cifra
que todavía podía retirarse.
"""
* status = #final
* code = CatalogoPruebas#LEGIOAG "Antígeno de Legionella en orina"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-orina-legionella)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-08-03T19:05:00+02:00"
* issued = "2026-08-03T19:40:00+02:00"
* valueCodeableConcept = ResultadosCualitativos#POS "Positivo"
* interpretation[0] = $INTERPRETACION#POS "Positive"
* note[0].text = "Resultado comunicado telefónicamente al facultativo peticionario y declarado a Salud Pública."


Instance: resultado-legionella-negativo
InstanceOf: ResultadoLab
Usage: #example
Title: "Antígeno de Legionella: NEGATIVO — no declarable"
Description: """
El control negativo del escenario, y hace falta: sin él, una regla que declarase **siempre** que ve
un `LEGIOAG` pasaría por buena.

Es la misma prueba, el mismo perfil y el mismo estado `final`. Lo único que cambia es el código del
valor, y con eso basta para que no se declare nada. Un negativo de una prueba EDO no es información
para Salud Pública: es información para el clínico, que sigue buscando la causa de la neumonía.
"""
* status = #final
* code = CatalogoPruebas#LEGIOAG "Antígeno de Legionella en orina"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-orina-legionella)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-08-03T19:06:00+02:00"
* issued = "2026-08-03T19:40:00+02:00"
* valueCodeableConcept = ResultadosCualitativos#NEG "Negativo"
* interpretation[0] = $INTERPRETACION#NEG "Negative"


Instance: declaracion-de-legionelosis
InstanceOf: NotificacionEDO
Usage: #example
Title: "Declaración de la legionelosis a Salud Pública"
Description: """
La obligación, convertida en algo que se puede seguir: **a quién**, **por qué resultado** y **en qué
estado va**.

Este ejemplo es el CONTRATO, no el circuito: quien crea estas tareas de verdad, las envía y recoge el
acuse es el notificador del ítem 48. Lo que el ítem 47 deja resuelto es lo de antes — decidir, sobre
códigos, que este resultado y no el otro obliga a declarar.

`businessStatus` va aparte de `status` porque son dos ciclos distintos: el `Task` puede estar
`in-progress` mientras la declaración está «enviada y sin acusar». Fundirlos haría imposible
distinguir «no lo hemos mandado» de «lo mandamos y no contestan», que es justo lo que hay que poder
decir cuando alguien pregunte si se declaró en plazo.
"""
* status = #in-progress
* intent = #order
* code = $TIPOS_DE_TAREA#fulfill "Fulfill the focal request"
* code.text = "Declaración de enfermedad de declaración obligatoria"
// Solo texto, y a propósito: un `Coding` sin `system` no es un código, es una cadena disfrazada —el
// validador lo dice—, y el vocabulario de estados frente a Redalerta lo fija el notificador del ítem
// 48, que es quien conoce el ciclo. Hasta entonces, `text` dice la verdad sin fingir codificación.
* businessStatus.text = "Enviada a Redalerta, pendiente de acuse"
* focus = Reference(resultado-legionella-positivo)
* for = Reference(paciente-ejemplo)
* requester = Reference(laboratorio-ejemplo)
* owner = Reference(salud-publica-ejemplo)
* authoredOn = "2026-08-03T19:41:00+02:00"
* lastModified = "2026-08-03T19:41:12+02:00"
* note[0].text = "Legionelosis. Declaración urgente por la posibilidad de un foco ambiental común."


Instance: salud-publica-ejemplo
InstanceOf: Organization
Usage: #example
Title: "Salud Pública — destinatario de la declaración (simulado)"
Description: """
El organismo al que se declara.

**Simulado, y a propósito.** El diseño (§15) fija que una integración inventada con una
administración real da falso realismo y no se puede validar: aquí hay un destinatario con la forma
que tendría, no la unidad de protección de la salud de ningún distrito sanitario concreto.

No lleva perfil `LaboratorioOrg` porque no es un laboratorio: es la contraparte, y perfilarla con las
reglas de un centro emisor —NICA, NIF— le exigiría identificadores que no le corresponden.
"""
* name = "Vigilancia Epidemiológica (simulado)"
* active = true
