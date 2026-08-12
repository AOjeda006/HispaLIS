// Escenario: la traza de acceso, con y sin PHI a la vista.
//
// Tres trazas del mismo día, elegidas porque cada una enseña algo que la anterior no puede: una
// lectura que sale bien, una búsqueda —que es donde el criterio tienta— y un acceso denegado.
//
// Lo que hay que mirar en las tres es lo que NO está. Ninguna lleva el nombre del paciente, ninguna
// lleva el número de historia y ninguna lleva `entity.query`, que es el elemento que el estándar
// reserva para la consulta ejecutada y que este perfil prohíbe. La segunda es la que lo demuestra: la
// búsqueda fue por NHC y en la traza no aparece.
//
// Datos SINTÉTICOS.

Instance: traza-lectura-de-resultado
InstanceOf: TrazaDeAcceso
Usage: #example
Title: "Traza: una facultativa lee un resultado"
Description: """
El caso normal, y el que más veces ocurre. Una lectura directa que sale bien.

`entity.what` apunta al resultado; el resultado **no viaja dentro**. Con la referencia se reconstruye
el acceso —qué se miró y cuándo—, que es para lo que sirve una traza. Con un volcado se reconstruiría
la historia clínica desde el registro de auditoría, que es exactamente lo que no puede pasar.

`patient` está porque la pregunta que un paciente tiene derecho a hacer es «¿quién ha visto lo mío?»,
y sin este elemento habría que resolver una a una las referencias de `entity` para contestarla.

**`agent.who` va por identificador y no por referencia**, aunque el `fhirUser` del testigo nombre a un
facultativo que sí está en el directorio. Dos motivos, y el segundo es el que decide. El primero es de
autoridad: quien afirma ese `fhirUser` es el proveedor de identidad, no el laboratorio, y una
referencia literal diría «este recurso, el mío». El segundo es que HAPI comprueba la integridad
referencial **al escribir**, así que con una referencia literal la traza de alguien con testigo válido
que **no** figura en el directorio no se puede guardar — y ése es precisamente el acceso que hay que
registrar (`adr-0030`). Se busca con `AuditEvent?agent:identifier=…|Practitioner/dra-alvarez`.

Y `source.observer`, por lo mismo: el servidor no se publica a sí mismo como recurso en su propia
proyección.
"""
* category[0] = $TIPOS_DE_TRAZA#rest "RESTful Operation"
* code = $INTERACCION_REST#read "read"
* action = #R
* severity = #informational
* recorded = "2026-08-03T19:52:11+02:00"
* outcome.code = $DESENLACE_DE_TRAZA#0 "Success"
* agent[0].type = $PAPEL_DE_AGENTE#humanuser "human user"
* agent[0].who.type = "Practitioner"
* agent[0].who.identifier.system = "https://aojeda006.github.io/HispaLIS/sid/usuario-del-testigo"
* agent[0].who.identifier.value = "Practitioner/dra-alvarez"
* agent[0].requestor = true
// ⚠️ R5: `network[x]`. En R4 esto era `agent.network` con `address` y `type` dentro.
* agent[0].networkString = "10.20.0.34"
* source.observer.identifier.system = "https://aojeda006.github.io/HispaLIS/sid/servidor"
* source.observer.identifier.value = "hispalis-backend"
* source.type[0] = $TIPOS_DE_TRAZA#rest "RESTful Operation"
* patient = Reference(paciente-ejemplo)
* entity[0].what = Reference(resultado-legionella-positivo)
* entity[0].role = $PAPEL_DE_OBJETO#4 "Domain Resource"


Instance: traza-busqueda-por-historia
InstanceOf: TrazaDeAcceso
Usage: #example
Title: "Traza: una búsqueda por número de historia — y el criterio no está"
Description: """
La traza que demuestra la regla.

La petición fue `GET [base]/Patient?identifier=…|20260803001`, es decir, **una búsqueda por el número
de historia clínica**. En la traza no aparece ese número por ninguna parte, y no es que se haya
recortado el texto: `entity.query` —el elemento que el estándar reserva para la consulta ejecutada, en
base64— está prohibido en el perfil, así que no hay dónde ponerlo.

Que vaya en base64 es justamente lo que lo hace peligroso: no se ve al leer el recurso, así que nadie
revisando trazas se daría cuenta de que las suyas llevan identificadores de paciente dentro. Es el
mismo razonamiento de `adr-0016`, que sacó los criterios de búsqueda de las URL registradas, aplicado
al sitio donde el propio estándar invita a ponerlos.

Lo que sí queda es lo que hace falta: que alguien buscó pacientes, quién, desde dónde y **qué le
salió** — el `Patient` devuelto va en `entity.what`. Con eso se reconstruye el acceso sin haber
guardado el criterio.
"""
* category[0] = $TIPOS_DE_TRAZA#rest "RESTful Operation"
* code = $INTERACCION_REST#search-type "search-type"
* action = #E
* severity = #informational
* recorded = "2026-08-03T19:48:02+02:00"
* outcome.code = $DESENLACE_DE_TRAZA#0 "Success"
* agent[0].type = $PAPEL_DE_AGENTE#humanuser "human user"
* agent[0].who.type = "Practitioner"
* agent[0].who.identifier.system = "https://aojeda006.github.io/HispaLIS/sid/usuario-del-testigo"
* agent[0].who.identifier.value = "Practitioner/dra-alvarez"
* agent[0].requestor = true
* agent[0].networkString = "10.20.0.34"
* source.observer.identifier.system = "https://aojeda006.github.io/HispaLIS/sid/servidor"
* source.observer.identifier.value = "hispalis-backend"
* source.type[0] = $TIPOS_DE_TRAZA#rest "RESTful Operation"
* patient = Reference(paciente-ejemplo)
* entity[0].what = Reference(paciente-ejemplo)
* entity[0].role = $PAPEL_DE_OBJETO#1 "Patient"


Instance: traza-acceso-denegado
InstanceOf: TrazaDeAcceso
Usage: #example
Title: "Traza: un cliente de sistema intenta exportar sin permiso"
Description: """
La traza que más falta hace, y la que se olvida: **la del intento que no llegó a ninguna parte**.

Un cliente de sistema pidió `POST [base]/Group/cohorte-legionelosis/$export` con un testigo que solo
traía `system/Group.rs`. Le faltaba `system/*.rs`, que es lo que cubre lo que el fichero se lleva de
verdad, así que el servidor contestó `403` y no se exportó nada.

Un registro que solo guardara los accesos correctos serviría para justificar lo que se hizo bien y
para nada más. Lo que se investiga tras un incidente es la serie de intentos fallidos, y por eso
`outcome` es `1..1` en el perfil: una traza sin desenlace no distingue «lo vio» de «lo intentó».

Fíjate también en el agente: `dataprocessor`, no `humanuser`. Detrás de un cliente de sistema no hay
nadie mirando una pantalla, y en SMART Backend Services eso es literal — el testigo se emite sin
usuario.
"""
* category[0] = $TIPOS_DE_TRAZA#rest "RESTful Operation"
* code = $INTERACCION_REST#operation "operation"
* action = #E
* severity = #warning
* recorded = "2026-08-04T02:15:47+02:00"
* outcome.code = $DESENLACE_DE_TRAZA#4 "Minor failure"
* outcome.detail[0].text = "El testigo no alcanza a lo que la exportación se lleva."
* agent[0].type = $PAPEL_DE_AGENTE#dataprocessor "data processor"
* agent[0].who.identifier.system = "https://aojeda006.github.io/HispaLIS/sid/cliente"
* agent[0].who.identifier.value = "almacen-analitico"
* agent[0].requestor = true
* agent[0].networkString = "10.20.9.7"
* source.observer.identifier.system = "https://aojeda006.github.io/HispaLIS/sid/servidor"
* source.observer.identifier.value = "hispalis-backend"
* source.type[0] = $TIPOS_DE_TRAZA#rest "RESTful Operation"
* entity[0].what = Reference(cohorte-legionelosis)
* entity[0].role = $PAPEL_DE_OBJETO#4 "Domain Resource"
