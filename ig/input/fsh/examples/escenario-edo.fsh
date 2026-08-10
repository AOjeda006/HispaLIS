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
Title: "Declaración de la legionelosis: acusada por Salud Pública"
Description: """
La obligación cumplida, y con qué se demuestra: **a quién**, **por qué resultado**, **en qué plazo** y
**con qué número de registro**.

Los dos estados dicen cosas distintas y por eso están los dos. `status = completed` es la tarea
cerrada dentro del laboratorio; `businessStatus = ACUSADA` es la administración diciendo que la tiene.
Fundirlos haría imposible distinguir «no lo hemos mandado» de «lo mandamos y no contestan», que es
justo lo que hay que poder decir cuando alguien pregunte si se declaró en plazo.

**El `output` es la prueba.** Sin número de registro esto no es una declaración hecha, por muy bien
que haya ido el envío: es un mensaje que salió. Va como `Identifier` y no como texto porque el número
es de Salud Pública y el `system` dice de quién es.

El plazo está en `restriction.period.end`: la legionelosis es de declaración **urgente**, veinticuatro
horas desde que el resultado quedó validado. De ahí también el `priority = stat`.
"""
* status = #completed
* intent = #order
* code = $TIPOS_DE_TAREA#fulfill "Fulfill the focal request"
* code.text = "Declaración de enfermedad de declaración obligatoria"
* businessStatus = EstadosDeclaracionEdo#ACUSADA "Acusada por Salud Pública"
* priority = #stat
* reason.concept = EnfermedadesEdo#LEGIONELOSIS "Legionelosis"
* focus = Reference(resultado-legionella-positivo)
* for = Reference(paciente-ejemplo)
* requester = Reference(laboratorio-ejemplo)
* owner = Reference(salud-publica-ejemplo)
* authoredOn = "2026-08-03T19:41:00+02:00"
* lastModified = "2026-08-03T19:41:12+02:00"
* restriction.period.end = "2026-08-04T19:41:00+02:00"
* output[0].type.text = "Número de registro de la declaración en Salud Pública"
* output[0].valueIdentifier.system = "https://ejemplo-svea.simulado/registro-de-declaraciones"
* output[0].valueIdentifier.value = "SVEA-2026-000123"
* note[0].text = "Legionelosis. Declaración urgente por la posibilidad de un foco ambiental común."


Instance: declaracion-de-salmonelosis-vencida
InstanceOf: NotificacionEDO
Usage: #example
Title: "Declaración fuera de plazo: enviada y sin acusar"
Description: """
El caso incómodo, que es el que hay que poder enseñar.

La obligación se registró, el envío salió y **Salud Pública no ha devuelto número de registro**. El
plazo terminó el 27 de julio y hoy sigue así. La declaración **no está hecha**, y el sistema lo dice
en vez de darla por buena porque el envío no dio error.

Así es como se encuentra, con el `SearchParameter` que publica esta guía y el `business-status`, que
sí es estándar:

```
GET [base]/Task?business-status=PENDIENTE,ENVIADA&vencimiento=lt2026-08-03
```

Existe como ejemplo por lo mismo que existe el resultado `preliminary`: es el estado que más fácil se
malinterpreta. Un `Task` con `status = in-progress` parece que va bien. Lo que dice de verdad es que
hay una obligación legal incumplida esperando a que alguien la mire.
"""
* status = #in-progress
* intent = #order
* code = $TIPOS_DE_TAREA#fulfill "Fulfill the focal request"
* code.text = "Declaración de enfermedad de declaración obligatoria"
* businessStatus = EstadosDeclaracionEdo#ENVIADA "Enviada, sin acuse"
// Ordinaria, no urgente: una salmonelosis se declara dentro de la semana epidemiológica. Que el plazo
// sea más largo no lo hace menos plazo.
* priority = #routine
* reason.concept = EnfermedadesEdo#SALMONELOSIS "Salmonelosis"
* focus = Reference(resultado-salmonella-positivo)
* for = Reference(paciente-ejemplo)
* requester = Reference(laboratorio-ejemplo)
* owner = Reference(salud-publica-ejemplo)
* authoredOn = "2026-07-20T09:14:00+02:00"
* lastModified = "2026-07-20T09:14:30+02:00"
* restriction.period.end = "2026-07-27T09:14:00+02:00"
* note[0].text = "Sin acuse tras los reintentos. Requiere gestión manual con la unidad de protección de la salud."


Instance: especimen-heces-salmonella
InstanceOf: EspecimenLab
Usage: #example
Title: "Heces para coprocultivo"
Description: "Muestra de heces de un caso de gastroenteritis aguda tras una comida colectiva."
* status = #available
* accessionIdentifier.system = "https://aojeda006.github.io/HispaLIS/sid/acceso"
* accessionIdentifier.value = "26-0198004"
* type = $SCT#119339001
* subject = Reference(paciente-ejemplo)
* receivedTime = "2026-07-20T08:40:00+02:00"
* collection.collectedDateTime = "2026-07-19T21:30:00+02:00"


Instance: resultado-salmonella-positivo
InstanceOf: ResultadoLab
Usage: #example
Title: "Coprocultivo: Salmonella POSITIVO — declarable, plazo ordinario"
Description: """
El otro extremo del plazo. Es igual de declarable que la legionelosis —el criterio es el mismo, un
`#POS` sobre una prueba con `enfermedad-edo`—, pero la salmonelosis es de declaración **ordinaria**: se
declara dentro de la semana epidemiológica, no en veinticuatro horas.

Que la urgencia dependa de la **enfermedad** y no de la prueba es el motivo de que el plazo viva en
`CodeSystem/enfermedades-edo` y no en el catálogo de pruebas: si mañana el laboratorio añade una PCR
de Salmonella, hereda el plazo sin que nadie lo copie.
"""
* status = #final
* code = CatalogoPruebas#COPROSALM "Coprocultivo: Salmonella"
* subject = Reference(paciente-ejemplo)
* specimen = Reference(especimen-heces-salmonella)
* performer[0] = Reference(facultativo-ejemplo)
* effectiveDateTime = "2026-07-20T09:10:00+02:00"
* issued = "2026-07-20T09:14:00+02:00"
* valueCodeableConcept = ResultadosCualitativos#POS "Positivo"
* interpretation[0] = $INTERPRETACION#POS "Positive"


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
