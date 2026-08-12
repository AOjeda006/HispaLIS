---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-10
tags: [adr, fhir, hapi, auditevent, integridad-referencial, auditoria, rgpd]
---

# ADR-0030: La traza del acceso que falla es la que el servidor se niega a guardar

- **Estado:** aceptado
- **Fecha:** 2026-08-10

## Contexto

El ítem 50 pide que **toda** lectura y escritura de la API deje su `AuditEvent`: quién, qué, cuándo y
desde dónde. La traza referencia lo accedido en `AuditEvent.entity.what`, que es una `Reference` — y
tiene que serlo, porque la alternativa es meter el recurso dentro y convertir el registro de
auditoría en una copia de la historia clínica.

Con el interceptor escrito y siete tests delante, seis pasaban y uno no: el que comprueba que **un
acceso fallido también deja rastro**. En el log aparecía esto:

```
ERROR  TrazaDeAcceso : No se ha podido registrar la traza de acceso a Patient. El acceso SÍ ocurrió.
ca.uhn.fhir.rest.server.exceptions.InvalidRequestException:
  HAPI-1094: Resource Patient/no-existe-este-paciente not found,
  specified in path: AuditEvent.entity.what
```

HAPI comprueba la **integridad referencial al escribir**: una referencia a un recurso que no existe
hace que el recurso entero se rechace. Aplicado a un `AuditEvent`, el resultado tiene una forma muy
concreta y muy mala:

> **La traza del acceso a un id que no existe —o a uno recién borrado— es la que más falta hace para
> investigar un incidente, y es exactamente la única que el servidor no deja guardar.**

Quien sondea ids de paciente a ver cuáles existen genera, en un servidor así, un registro de
auditoría **vacío**: todos sus intentos fallidos se caen al escribirse.

Y hay una segunda mitad, que apareció al pensar en el borrado. HAPI comprueba la integridad
referencial **también al borrar**: un recurso al que apunta otro no se puede retirar. Como la traza
referencia *todo lo que alguien ha mirado*, el primer `AuditEvent` sobre un paciente lo convierte en
indestructible. Eso rompe dos cosas a la vez: el reconciliador (§15) deja de poder retirar un recurso
huérfano, y **el derecho de supresión del RGPD se vuelve imposible de ejercer por culpa del registro
que existe justamente para respetarlo**.

## Decisión

Dos medidas, una por cada mitad.

**1. La referencia es literal solo si el servidor llegó a devolver el recurso.** Lo que salió por la
respuesta existe y va como `Reference.reference`. Lo que únicamente se pidió —el id de la URL en un
`DELETE`, o en cualquier petición que acabó en `4xx`— va como **referencia lógica**: `Reference.type`
con el tipo y `Reference.identifier` con `Tipo/id` bajo un `system` propio. FHIR admite las dos
formas, y la lógica es además la más honesta: dice «se pidió esto» sin afirmar que eso exista.

**1 bis. Y quien llamó tampoco es una referencia literal.** *(añadido el 2026-08-12, tras verlo por
segunda vez.)* Con la medida 1 puesta y siete tests en verde, el registro seguía perdiendo trazas —
esta vez por `AuditEvent.agent.who`:

```
ERROR TrazaDeAcceso : No se ha podido registrar la traza de acceso a Patient. El acceso SÍ ocurrió.
  HAPI-1094: Resource Practitioner/dra-alvarez not found, specified in path: AuditEvent.agent.who
```

El `fhirUser` del testigo se escribía como `Reference("Practitioner/…")`, así que la traza de
**cualquiera cuyo `fhirUser` no esté en el directorio del laboratorio** se caía entera. Y ése es,
otra vez, el acceso que más falta hace registrar: alguien con un testigo válido que no figura entre
nuestros profesionales.

El fondo es de autoridad y no de robustez: **el `fhirUser` lo afirma el proveedor de identidad, no
este servidor.** Una referencia literal diría «este recurso, el mío», que es una afirmación que el
laboratorio no está en condiciones de hacer sobre un dato que le llega en un JWT. Así que `agent.who`
va **siempre** por identificador, con `Reference.type` y `Reference.identifier`, y se busca con
`AuditEvent?agent:identifier=<sistema>|Practitioner/dra-alvarez`. `source.observer` iba ya así por la
misma razón.

Lo que esto enseña, y es lo que se lleva uno de haberlo visto dos veces: **la pregunta no es «¿existe
esto?» sino «¿lo he publicado yo?»**. Si la respuesta es no —porque venía de la URL, del testigo, o
de cualquier sitio que no sea la respuesta que este servidor acaba de dar—, la referencia es lógica.

**2. La integridad referencial al borrar se desactiva para los caminos por los que la traza apunta**,
y solo para esos:

```java
ajustes.setEnforceReferentialIntegrityOnDeleteDisableForPaths(Set.of(
        "AuditEvent.entity.what", "AuditEvent.patient", "AuditEvent.agent.who",
        "AuditEvent.source.observer", "AuditEvent.basedOn", "AuditEvent.encounter"));
```

La regla que hay detrás, y que es lo que conviene recordar: **una traza no mantiene vivo lo que se
limitó a observar.** Lo que queda tras el borrado es la constancia de que alguien lo miró, apuntando
a un id que ya no resuelve — que es exactamente lo que hay que conservar.

## Consecuencias

- El registro de auditoría **incluye los intentos fallidos**, que es la mitad para la que existe.
- Los recursos se pueden seguir borrando: el reconciliador funciona y el derecho de supresión también.
- La traza tiene **dos formas** de nombrar un recurso, y quien la explote tiene que leer las dos. Es
  el precio, y se paga a cambio de no perder ningún registro. Queda escrito en el javadoc de
  `TraductorDeTraza.referenciaA` y en el perfil `TrazaDeAcceso`.
- El ajuste de integridad al borrar es **global del servidor**, aunque acotado por camino: si mañana
  otro recurso necesita lo mismo, se añade a esa lista y no se toca la global.

## Alternativas descartadas

- **Apagar `enforceReferentialIntegrityOnWrite`.** Resolvería la primera mitad de una línea, y a
  cambio dejaría entrar referencias colgando en *todos* los recursos, incluidos los clínicos. El
  laboratorio comprueba en el dominio los que gobierna, pero no los datos maestros: un
  `ServiceRequest.requester` apuntando a un `Practitioner` inexistente pasaría sin que nadie avise.
- **`setAutoCreatePlaceholderReferenceTargets(true)`.** HAPI crea el recurso que falta. Para un
  registro de auditoría es lo peor imaginable: el sondeo de ids inexistentes **crearía** esos
  pacientes.
- **No registrar el id cuando el acceso falla.** La traza diría «alguien intentó leer un paciente y
  se le negó», sin decir cuál. Sirve para una estadística y no para una investigación.
- **Guardar la traza fuera del servidor FHIR** (tabla propia, o un servidor de auditoría aparte). Es
  lo que hace un despliegue grande y probablemente lo correcto a escala, pero aquí rompería el
  invariante 3 —un solo camino de escritura— y dejaría la traza sin poder consultarse por la API con
  los `SearchParameter` estándar de `AuditEvent`, que es la mitad de su valor.

## Lo reutilizable

Vale para cualquier servidor FHIR que audite con `AuditEvent` en el mismo almacén que los datos, y en
general para cualquier registro que referencie lo que observa:

1. **Un registro de auditoría no puede depender de que exista lo auditado.** En cuanto lo hace, deja
   de registrar precisamente los casos anómalos, que son su razón de ser. Si el almacén impone
   integridad referencial, la referencia del registro tiene que poder ser lógica. Y **no basta con
   arreglar el elemento donde apareció**: `entity.what`, `agent.who` y `source.observer` son el mismo
   problema tres veces. El criterio que los cubre a los tres es *«¿lo he publicado yo?»*, no *«¿existe
   ahora mismo?»*.
2. **Un registro de auditoría no puede impedir el borrado de lo que observó.** Si lo impide, colisiona
   de frente con la normativa que lo exige — y la colisión aparece meses después, el día que alguien
   ejerce su derecho de supresión y el sistema contesta que no puede.
3. Y la comprobación que lo caza: **probar el camino que falla**. Los seis tests del acceso correcto
   pasaban. El defecto solo lo vio el séptimo, que pedía un recurso inexistente.
