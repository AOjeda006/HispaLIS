Profile: TrazaDeAcceso
Parent: AuditEvent
Id: traza-de-acceso
Title: "Traza de acceso a la API"
Description: """
Constancia de un acceso a la API del laboratorio: **quién**, **qué**, **cuándo** y **desde dónde**.

Se escribe una por interacción REST —lectura y escritura, la que sale bien y la que falla—, y la
escribe el servidor. Es la justificación de trazabilidad de **D17**: la ISO 15189 está fuera de
alcance como requisito, pero su exigencia de poder reconstruir quién hizo qué se cumple igual, con
`Provenance` para quién firmó un resultado y con esto para quién lo miró.

## Lo que este perfil PROHÍBE, y por qué es lo importante

Un `AuditEvent` es el recurso que más fácil se llena de datos que no debería tener, porque la
intuición empuja en el sentido contrario: para investigar un incidente parece que cuanto más se
guarde, mejor. Es al revés. La traza se conserva años, la lee gente de sistemas y no de la consulta,
y se exporta a un SIEM: **lo que entre ahí sale del ámbito clínico para siempre.**

Por eso el perfil cierra dos elementos a `0..0`:

- **`entity.query`** — el estándar lo reserva para la consulta que se ejecutó, **en base64**. Es
  exactamente donde acabaría el número de historia de `GET [base]/Patient?identifier=…`, y en base64
  ni siquiera se ve al leer el recurso. Es el mismo razonamiento que dejó los criterios de búsqueda
  fuera de las URL registradas (`adr-0016`), aplicado al sitio donde el estándar invita a ponerlos.
- **`entity.detail`** — pares nombre/valor libres. Un cajón de sastre en un registro que se conserva
  años se llena, y lo que se guarda ahí nadie lo revisa.

Lo que sí va es **`entity.what`**: una referencia al recurso al que se accedió. Con la referencia se
reconstruye el acceso; con un volcado se reconstruye la historia clínica, que es otra cosa.

## Una traza nombra los recursos de DOS formas, y hay que leer las dos

Quien explote estas trazas tiene que contar con ello, así que se declara aquí y no se descubre
consultando:

| Elemento | Cómo se escribe |
|---|---|
| `entity.what` | **Referencia literal** si el servidor llegó a devolver el recurso; **lógica** (`type` + `identifier`) si solo se pidió — un `DELETE`, o cualquier petición que acabó en `4xx` |
| `agent.who` | **Siempre lógica.** El `fhirUser` lo afirma el proveedor de identidad, no este servidor |
| `source.observer` | **Siempre lógica.** El servidor no se publica a sí mismo como recurso en su propia proyección |
| `patient` | **Siempre literal**, y solo cuando el servidor devolvió algo de esa persona |

El motivo es medido, no teórico (`adr-0030`): el almacén comprueba la integridad referencial **al
escribir**, así que una referencia literal a algo que no existe **tumba la traza entera**. Aplicado a
un registro de auditoría, eso significa perder exactamente los casos por los que existe — el acceso a
un id inventado, y el de alguien con testigo válido que no figura en el directorio del laboratorio.
Buscar por la forma lógica es una consulta más larga (`AuditEvent?agent:identifier=<sistema>|<Tipo/id>`)
a cambio de que la traza llegue a escribirse.

## R5 no es R4

`AuditEvent` es de los recursos que más cambian, y un ejemplo de R4 copiado aquí no valida:

| R4 | R5 |
|---|---|
| `type` (`Coding`) + `subtype` (`Coding 0..*`) | **`category` (`CodeableConcept 0..*`) + `code` (`CodeableConcept 1..1`)** |
| `outcome` (código `0\|4\|8\|12`) + `outcomeDesc` | **`outcome` (elemento con `code` `Coding` y `detail`)** |
| `agent.network` (elemento con `address` y `type`) | **`agent.network[x]`** (`Reference \| uri \| string`) |
| `agent.who` `0..1`, más `altId`, `name`, `media` | **`agent.who` `1..1`**; los otros tres, eliminados |
| `entity.type`, `.lifecycle`, `.name`, `.description` | **eliminados** |
| `source.site` (`string`) | **`source.site` (`Reference(Location)`)** |
| — | **nuevos: `severity`, `occurred[x]`, `patient`, `encounter`, `authorization`, `basedOn`** |
"""

// QUÉ CLASE DE ACTO. `category` es la familia —aquí siempre una interacción REST— y `code` la
// interacción concreta. En R4 esto eran `type` y `subtype`, y con otros tipos de dato.
* category 1..* MS
* code 1..1 MS
* code ^short = "La interacción REST: `read` | `search-type` | `create` | `update` | `delete`…"

* action 1..1 MS
* action ^short = "`C` | `R` | `U` | `D` | `E`. Es lo que permite listar «todas las escrituras de ayer»"

* severity MS
* recorded 1..1 MS
* recorded ^short = "Cuándo se levantó acta"

* outcome 1..1 MS
* outcome.code 1..1 MS
* outcome ^short = "Si el acceso salió bien o no. El que falla es el que más falta hace al investigar"

// QUIÉN. `agent.who` es `1..1` ya en el núcleo de R5 —en R4 era `0..1`—, así que aquí solo se marca
// como soportado. Lo que sí se añade es exigir `requestor`: sin él no se distingue a quien pidió el
// acto de los sistemas que solo participaron en él.
* agent 1..* MS
* agent.who 1..1 MS
* agent.who ^short = "Quién llamó, SIEMPRE por identificador: el `fhirUser` lo afirma el proveedor de identidad, no este servidor"
* agent.requestor 1..1 MS
* agent.type MS
* agent.network[x] MS
* agent.network[x] ^short = "⚠️ R5: `network[x]`, no el `agent.network` con `address`/`type` de R4"

// QUIÉN LEVANTA ACTA. Sin esto, una traza recogida de varios sistemas no se puede atribuir.
* source 1..1 MS
* source.observer 1..1 MS
* source.observer ^short = "El servidor que registra el acceso"

// A QUÉ SE ACCEDIÓ — por referencia.
* entity MS
* entity.what 1..1 MS
* entity.what ^short = "Referencia al recurso accedido. Nunca el recurso"

// Y LO QUE NO SE GUARDA, cerrado en el contrato y no solo en el código. Ver la descripción.
* entity.query 0..0
* entity.query ^short = "PROHIBIDO: es donde acabaría el número de historia de una búsqueda"
* entity.detail 0..0
* entity.detail ^short = "PROHIBIDO: un cajón de pares libres en un registro que se conserva años"

// De quién eran los datos, como referencia. Es lo que permite contestar «¿quién ha visto la historia
// de esta persona?», que es la pregunta que un paciente tiene derecho a hacer.
* patient MS
* patient only Reference(PacienteLabES)
