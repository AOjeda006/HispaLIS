---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-03
tags: [adr, terminologia, snomed, loinc, hapi, snowstorm, conceptmap]
---

# ADR-0006: Servidor de terminología ligero e intercambiable (HAPI), no Snowstorm de entrada

- **Estado:** aceptado
- **Fecha:** 2026-08-03
- **Decisión de diseño:** D14 (`docs/diseno.md` §5)

## Contexto

La terminología es el corazón del dominio de un laboratorio: LOINC en `Observation.code` y
`ServiceRequest.code`, UCUM en las unidades, SNOMED CT (Edición Española + extensión nacional del SNS)
en tipos de muestra y motivos, y el **catálogo local del laboratorio** — el "dialecto" del que habla
la propia documentación del MPA de Diraya — mapeado a LOINC con un `ConceptMap`.

Si la terminología no se pone como caja obligatoria desde el día uno, aparece un `Map<String,String>`
y ya no sale. La opción "de libro" es **Snowstorm**, el servidor oficial de SNOMED International.

## Decisión

**Servidor de terminología de HAPI**, desplegado como **servicio aparte**, cargado con **LOINC 2.82**,
**THO 7.3.0** y **subconjuntos curados** de SNOMED español, más el `CodeSystem` del catálogo local y
los `ConceptMap`. La terminología se resuelve **contra la API estándar**: `$expand`, `$lookup`,
`$validate-code`, `$translate`. **Snowstorm queda documentado como intercambio futuro**, no
implementado.

## Consecuencias

**Positivas**

- **El servidor es intercambiable:** como todo se resuelve contra operaciones estándar, migrar a
  Snowstorm después es **cambiar una URL**. Empezar ligero es gratis; empezar pesado, no.
- La lección del proyecto —el *binding*, el contrato de operaciones y el `ConceptMap` del catálogo
  local— se aprende igual, y es lo que realmente importa.
- Cabe en el presupuesto de esfuerzo de un proyecto de una persona.

**Negativas, asumidas**

- **Solo subconjuntos curados de SNOMED**, no la edición completa: hay que decidir y mantener qué
  entra. A cambio, obliga a curar el catálogo, que es trabajo útil.
- Las consultas jerárquicas complejas de SNOMED (ECL avanzado) quedan fuera de lo que se puede
  demostrar hasta migrar a Snowstorm.
- **Hay que declarar la versión exacta del *release*** de la Edición Española cargada, o los
  `display` dejan de ser reproducibles.
- **Licencia:** SNOMED CT es gratuita en territorio español previo registro, pero **sin
  redistribución** — condiciona el repositorio donde se archive el material.

## Alternativas consideradas

- **Snowstorm desde el día uno** — es el servidor oficial y la opción correcta a escala, pero exige
  Elasticsearch y varios GB de RAM para la edición completa. Consume presupuesto de esfuerzo sin
  enseñar nada nuevo sobre el problema del proyecto.
- **Sin servidor de terminología, con listas en el código** — descartado explícitamente: es el fallo
  que la convención propia nombra (`Map<String,String>` de códigos) y del que no se sale.
- **Servicio de terminología público externo** — descartado: no cubre la Edición Española ni el
  catálogo local, que es precisamente el eje del proyecto.
