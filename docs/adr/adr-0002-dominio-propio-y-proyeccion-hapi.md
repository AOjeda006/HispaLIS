---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-03
tags: [adr, fhir, arquitectura, hapi, transaccion, cqrs, proyeccion]
---

# ADR-0002: Dominio propio con proyección HAPI en la misma transacción

- **Estado:** aceptado
- **Fecha:** 2026-08-03
- **Decisión de diseño:** D3 (`docs/diseno.md` §9, §10)

## Contexto

Hay tres formas de montar un sistema clínico sobre FHIR: usar un servidor FHIR como núcleo (los
recursos **son** el modelo), escribir una fachada FHIR a mano sobre un dominio propio, o tener un
dominio propio y **proyectar** hacia un servidor FHIR.

El modelo publicado y el modelo propio **no coinciden, y no deben**. Un recurso FHIR tiene
opcionalidad enorme, `[0..*]` por todas partes y ninguno de los invariantes del negocio: *rechazado ⇒
no puede producir resultado*, *un informe solo se emite con todas las líneas resueltas*, *un resultado
crítico exige doble validación*. Persistir recursos FHIR como entidades pierde exactamente eso.

Al mismo tiempo, **FHIR REST exige leer-lo-que-acabas-de-escribir**: se devuelve `201 Created` con
`Location: Observation/123`, y si la proyección fuese asíncrona el `GET` inmediato daría `404`. No es
un detalle de rendimiento: **es incumplir la norma**.

## Decisión

**Núcleo de dominio propio como fuente de verdad, con HAPI FHIR JPA como proyección de lectura**,
escrita **síncrona y en la misma transacción** que el dominio: mismo PostgreSQL, esquemas distintos
(`dominio`, `fhir`, `outbox`), **un solo `@Transactional`**. La proyección llama a las DAOs de HAPI
(`IFhirResourceDao`) para que se pueblen sus índices de búsqueda. La lectura va **directa** a las DAOs
de HAPI, con cero mapeo en tiempo de lectura. **Kafka no alimenta el modelo de lectura**: alimenta
todo lo demás (notificaciones, notificador EDO, analítica), vía **outbox transaccional**.

## Consecuencias

**Positivas**

- Los invariantes del negocio viven donde se pueden expresar y probar por TDD, no repartidos por los
  `ResourceProvider`.
- Read-your-writes queda garantizado por construcción, no por suerte de temporización.
- **HAPI JPA da gratis** lo caro de reimplementar: búsqueda por `SearchParameter`,
  `_include`/`_revinclude`, paginación por `Bundle.link`, `_history`, `ETag`/`versionId`, `$validate`,
  `$export` y Subscriptions.
- El outbox evita perder hechos si el broker está caído.

**Negativas, asumidas**

- **Acoplamiento a las DAOs de HAPI** dentro de la transacción del dominio. Funciona (mismo
  *datasource*), pero ata a su API interna: cada actualización de HAPI es un punto de vigilancia.
- **Doble escritura del mismo hecho:** dominio y proyección pueden divergir por un bug de mapeo. Hace
  falta un **reconciliador** que recorra el dominio y regenere la proyección, y debe ser la **vía de
  recuperación oficial**, no un script de emergencia. Planificado en el hito 2.
- Escribir dos veces encarece la escritura. Aceptable: el perfil de un SIL es escritura moderada y
  lectura intensa.

## Alternativas consideradas

- **Servidor FHIR como núcleo** — descartado: los invariantes del negocio no se pueden expresar en un
  `StructureDefinition`, y acabarían como validación dispersa o simplemente ausentes. El proyecto
  degeneraría en "un HAPI con perfiles".
- **Fachada FHIR escrita a mano sobre el dominio** — descartado: obliga a reimplementar búsqueda,
  `_include`, paginación, `_history`, `ETag` y `$export`. Es meses de trabajo que HAPI ya resuelve, y
  ninguno enseña nada sobre el dominio.
- **Proyección asíncrona (event sourcing puro)** — descartado: rompe read-your-writes, que es
  requisito normativo de FHIR REST.
