---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-03
tags: [adr, hl7-v2, mllp, integracion, mirth, hapi, spring]
---

# ADR-0005: Motor de integración propio con HAPI HL7v2, frente a Mirth Connect

- **Estado:** aceptado
- **Fecha:** 2026-08-03
- **Decisión de diseño:** D11 (`docs/diseno.md` §7)

## Contexto

Los sistemas heredados (HIS de la clínica, analizadores) hablan **HL7 V2.5.1 sobre MLLP**, no HTTP.
Hace falta un punto de conversión v2 → FHIR R5 explícito y auditable. La opción de mercado es un motor
de interfaces dedicado (Mirth Connect / OpenIntegrationEngine); la alternativa es un servicio propio.

## Decisión

Un **servicio Spring Boot propio** con la librería **HAPI HL7v2** (`ca.uhn.hl7v2`), que aporta
listener MLLP, parser y generación de acuses. Estructura del canal:
`origen → filtro → transformador → destino`. El motor **traduce y escribe contra la propia API FHIR**
(D5), autenticándose como cliente `system/` vía SMART Backend Services.

## Consecuencias

**Positivas**

- **Mismo lenguaje y misma cadena de construcción que el backend** → encaja en el monorepo y en la CI
  sin un *runtime* aparte con su propio modelo de despliegue.
- **Canales como código por construcción.** La convención propia exige *"despliegue de canales por el
  mismo circuito que el código, no editando en la consola de producción"*. Con Mirth eso es una
  disciplina que hay que imponerse **contra** la herramienta; con un servicio propio es lo único
  posible.
- **El trabajo que ahorra Mirth no es el que enseña.** HAPI HL7v2 ya da parser, MLLP y acuses; lo que
  se escribe es la lógica del canal, que es la parte que interesa.
- **Un solo camino de escritura** (D5): las mismas validaciones, invariantes y auditoría que cualquier
  otro cliente. Si el motor invocara comandos de dominio directamente, habría dos puertas con
  garantías distintas.

**Negativas, asumidas**

- Se pierden el **almacén de mensajes, los reintentos y la consola de reproceso** que Mirth trae
  hechos. Hay que construir: **guardar el mensaje original íntegro**, **DLQ** y un **punto de
  reproceso idempotente**. Es trabajo del hito 2 — y es lo que las convenciones de integración exigen
  de todos modos.
- Deduplicación por `MSH-10` y normalización de charset (`MSH-18`) son responsabilidad del canal, no
  regaladas por la herramienta.

## Nota sobre MLLP — trampa documental registrada

El apéndice B (*Lower Layer Protocols*) de V2.5 y V2.5.1 **está vacío** y remite a otro documento; ese
documento es un estándar de **HL7 Version 3** (*Transport Specification — MLLP, Release 2*), y está
**retirado desde el 16 de mayo de 2025, sin sustituto designado**. **Hoy no existe un estándar HL7
vigente para MLLP**, mientras MLLP sigue transportando prácticamente todo el tráfico V2 en producción.

**Impacto en el código: ninguno.** HAPI HL7v2 implementa el *framing* (`0x0B` … `0x1C 0x0D`) y nunca
se escribe a mano. Lo que falta es **fuente citable**, no código. Este hallazgo debe destilarse a
`interoperabilidad/hl7-v2/` de la biblioteca al terminar el proyecto.

## Alternativas consideradas

- **Mirth Connect / OpenIntegrationEngine** — descartado: brilla operando decenas de interfaces con un
  equipo de integración dedicado; aquí añade un *runtime* y un modelo de despliegue extra, y empuja
  hacia editar canales en consola.
- **Que el motor invoque comandos de dominio directamente** — descartado por D5: dos caminos de
  escritura con garantías distintas.
- **Aceptar v2 en la API FHIR** — descartado por D4: funde dos contratos en una puerta y el mapeo deja
  de poder auditarse, que es justo el fallo que el motor existe para evitar.
