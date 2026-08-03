---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-03
tags: [adr, fhir, r5, r4, version, interoperabilidad]
---

# ADR-0001: FHIR R5 (5.0.0) como versión del proyecto, frente a R4

- **Estado:** aceptado
- **Fecha:** 2026-08-03
- **Decisión de diseño:** D1 (`docs/diseno.md` §1, §2)

## Contexto

La convención propia de la biblioteca fija *"R4 para producir hoy, R5 como referencia normativa"*:
R4 es lo que hablan los servidores reales, las guías nacionales y las certificaciones. HispaLIS es
una **simulación** con fines de aprendizaje y demostración, no un sistema que deba interoperar con
terceros en producción, y varios de los ejes que el proyecto quiere ejercitar
(`Observation.triggeredBy`, `SubscriptionTopic` de primera clase, `Coverage.kind` obligatorio)
existen **solo** en R5.

## Decisión

**Fijar FHIR R5 (`hl7.fhir.r5.core@5.0.0`)** como única versión del proyecto, declarada en
`sushi-config.yaml` y en el `CapabilityStatement`. No se soporta R4 ni se ofrece conversión.

## Consecuencias

**Positivas**

- Se puede usar `Observation.triggeredBy` para las pruebas reflejas, `SubscriptionTopic` +
  `Subscription` como recursos de primera clase y `$export` + `Group` sin apaños.
- `Coverage.kind` es `1..1` obligatorio en R5, y su distinción `self-pay` vs `insurance` es
  **exactamente** la del negocio de un laboratorio privado: el estándar fuerza a modelarlo bien.

**Negativas, asumidas**

- **No hay IG existente sobre la que apoyarse:** US Core es R4 e IPS 2.0.1 es R4. Los nueve perfiles
  se escriben desde cero.
- **Synthea y la mayoría de generadores producen R4** → resuelto por D15 (generador propio).
- **Menos red de seguridad:** pocos servidores públicos de prueba y suites de conformancia para R5.
- **Trampa permanente:** todo ejemplo, tutorial, respuesta de IA o librería basada en R4 falla en los
  nueve elementos de §2.1 del diseño. Esa tabla se repite en `ig/CLAUDE.md` y `backend/CLAUDE.md`
  precisamente porque es el error que más caro sale.

## Alternativas consideradas

- **R4 (4.0.1)** — lo real hoy y lo que sostiene la convención propia. Descartado porque obligaría a
  emular con apaños (`Subscription.criteria`, extensiones para reflejas) justo los mecanismos que el
  proyecto existe para enseñar; una simulación es el caso legítimo de R5.
- **R4B (4.3.0)** — descartado: no es "R4 con parches", es una release aparte que **quita 18 tipos de
  recurso presentes en R4.0.1** y añade 13 con nombres nuevos. No es un punto intermedio más seguro.
