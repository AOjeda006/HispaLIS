---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-03
tags: [adr, monorepo, ci, organizacion, ig, claude-md, agentes]
---

# ADR-0004: Monorepo único con la IG dentro, y un `CLAUDE.md` por componente

- **Estado:** aceptado
- **Fecha:** 2026-08-03
- **Decisiones de diseño:** D10 y D20 (`docs/diseno.md` §13)

> **Nota de entrega (2026-08-15).** La mitad **D10** de esta decisión —monorepo con la IG dentro y
> CI filtrada por rutas— sigue en pie tal cual. La mitad **D20** —los `CLAUDE.md`, el `AGENTS.md` y
> el `docs/PLAN.md`— era andamiaje de desarrollo y **ya no está en el árbol**: se borró al entregar,
> en el commit **`5aaaef8`**, y `git revert 5aaaef8` lo devuelve entero. El texto de abajo se
> mantiene sin tocar porque es el registro de la decisión, no una descripción del repositorio de
> hoy; qué contenía cada uno de aquellos ficheros y dónde quedó está en
> `docs/inventario-desmontaje.md`.

## Contexto

El sistema tiene ocho componentes en cuatro *toolchains*: IG (FSH), backend y motor (Java), web
(TypeScript), app (Dart), simuladores (Python) e infraestructura (YAML). La opción natural —un repo
por componente— choca con dos hechos: **el contrato es compartido y cambia a la vez** (la IG define
los perfiles que el backend valida, la web consume y el motor produce), y el proyecto lo desarrolla
**una sola persona**.

En paralelo, un único `CLAUDE.md` raíz que importase las convenciones de Java, Spring, TypeScript,
Angular, Dart, Flutter, Python, SQL **y** los siete subtemas de interoperabilidad sería enorme, y
contradiría la regla propia *"30 líneas útiles valen más que 300 que nadie lee"*.

## Decisión

1. **Monorepo único**, con la **IG dentro** (`ig/`), publicada a GitHub Pages desde `ig/output/` por
   una Action.
2. **`CLAUDE.md` raíz** con la parte fija (memoria, commits, clarificación, resumabilidad), los
   invariantes de proyecto y **solo** lo transversal e interoperabilidad; **más un `CLAUDE.md` por
   subproyecto** que importa únicamente las convenciones de su stack. Los `principios/` van en el raíz
   —que siempre está cargado— porque los `convenciones.md` de los cuatro lenguajes los declaran todos
   como requisito.
3. **CI con filtrado por `paths:` desde el primer día**, un workflow por componente.

## Consecuencias

**Positivas**

- Tocar un perfil es **un commit atómico que pasa o rompe CI de golpe**, en vez de un baile de
  versiones a tres bandas entre repos.
- **La IG no diverge del código que la implementa** — el fallo más común en proyectos FHIR.
- La demo end-to-end (`docker compose up`) es trivial.
- **Un solo `PLAN.md` y un solo historial**, que es lo que hace funcionar el protocolo de
  resumabilidad del agente.
- Cada agente carga solo el contexto de lo que está tocando; el raíz no crece sin control.

**Negativas, asumidas**

- El monorepo mezcla cuatro *toolchains*. **La CI debe filtrar por ruta desde el primer día** o cada
  cambio en Flutter recompilará el backend. Es obligación, no recomendación.
- Los `CLAUDE.md` por componente hay que **mantenerlos sincronizados** cuando cambia un stack, y las
  rutas de import llevan **un `../` más** desde un subdirectorio
  (`@../../BibliotecaDocumentacion/…`): un error de profundidad rompe silenciosamente el arranque del
  agente. Verificar al crearlas.
- Un checkout completo trae todo, aunque solo se toque un componente.

## Alternativas consideradas

- **Un repo por componente** — descartado: coordinar ocho repos es un impuesto que solo se paga
  cuando hay equipos separados, y el acoplamiento por contrato aquí es real, no accidental.
- **IG en repo aparte** — descartado: es exactamente el montaje en el que la guía y la implementación
  divergen.
- **Un único `CLAUDE.md` raíz con todos los imports** — descartado: contexto que nadie lee y que
  degrada al agente en cada turno.
