# AGENTS.md — Contrato de trabajo del agente (HispaLIS)

Este documento es el **contrato operativo**: `CLAUDE.md` dice *qué* convenciones seguir; este dice
*cómo* proceder. Ante conflicto, mandan las convenciones importadas en `CLAUDE.md`.

## Ciclo de trabajo (cada turno)

1. **Orientarse:** lee `docs/PLAN.md` y el último commit. Localiza el **primer ítem no completado**
   del checklist. No reconstruyas el estado de memoria: el PLAN es la verdad.
2. **Clarificar (puerta de arranque):** si hay decisiones **esenciales** sin especificar con varias
   opciones viables, pregúntalas **todas juntas** y **no avances** hasta resolverlas. Anota las
   respuestas en `PLAN.md` → *Decisiones*. **D1–D20 no se reabren.**
3. **Ejecutar un paso:** aborda **un** ítem del checklist a la vez, respetando las convenciones
   importadas. Antes de tocar un subproyecto, lee su `CLAUDE.md` propio.
4. **Verificar:** ejecuta build/tests/lint del componente (tabla de abajo). Un paso no está "hecho"
   hasta cumplir su **criterio de aceptación** en `PLAN.md`.
5. **Registrar:** marca el ítem, actualiza *Estado actual*, y **commitea** (modo git: `commit`).
6. **Cerrar el turno limpio:** `PLAN.md` coherente y árbol en verde.

## Definición de "hecho"

Un ítem está terminado cuando: cumple su **criterio de aceptación**, pasa build/tests/lint, respeta
las convenciones, **no deja `TODO` en el código**, y su cambio está reflejado en `PLAN.md` y
commiteado.

Añadidos propios de este proyecto:

- **Todo recurso FHIR de ejemplo valida** contra su perfil con el validador oficial
  (`hl7.fhir.r5.core@5.0.0`). Un recurso que no valida no cuenta como hecho.
- **Todo invariante de negocio nuevo llega con su test en rojo primero** (TDD).
- **Ningún artefacto generado se commitea:** `ig/output/`, `ig/temp/`, `ig/input-cache/`,
  `ig/fsh-generated/`, `target/`, `build/`, `node_modules/`, `.venv/`.

## Cómo retomar tras `/compact`

El `/compact` borra el contexto conversacional, **no** el disco:

1. Lee `docs/PLAN.md` (checklist + *Estado actual* + *Decisiones*) y `git log`.
2. Repite la **puerta de clarificación**: ¿surgió algo esencial no decidido? Pregunta antes de seguir.
3. Continúa por el primer ítem no marcado. Si algo quedó a medias, *Estado actual* debe decir
   exactamente dónde retomar; si no lo dice, es un fallo del turno anterior — deduce lo mínimo del
   `git log`/diff, anótalo y sigue.

## Reglas de oro

- **No trabajes sobre suposiciones esenciales.** Preguntar > adivinar.
- **El estado vive en disco.** Si importa para continuar, está en `PLAN.md` o en git.
- **Pasos pequeños y verificados.** Commits atómicos = puntos de retorno seguros.
- **No amplíes el alcance** por tu cuenta: lo que no esté en `PLAN.md` se propone, no se hace. En
  particular, **no adelantes trabajo de los hitos 2 y 3** (Kafka, HL7 v2, Keycloak, Flutter, EDO).
- **Registra los aprendizajes transversales como ADR** (`docs/adr/adr-NNNN-titulo-corto.md`, plantilla
  `patrones/plantilla-adr.md` de la biblioteca). No edites la biblioteca a mitad de proyecto.

## Modo git del encargo

**`commit`** — commits locales **firmados**, con la identidad
`Andrés Ojeda Rodríguez <andresojedarodriguez@gmail.com>` y **ningún trailer ajeno**. **Push solo
cuando el usuario lo pida.** No abrir PR salvo petición explícita. Si `git config commit.gpgsign` no
es `true`, **no commitees** y avisa (ver `CLAUDE.md` §3).

## Comandos por componente (parte variable)

| Componente | Build | Tests | Lint / formato | Arranque |
|---|---|---|---|---|
| `ig/` | `sushi .` + `java -jar publisher.jar -ig .` | validador oficial sobre `input/examples/` | `sushi . --strict` | — (salida en `ig/output/`) |
| `backend/` | `./mvnw -q package` | `./mvnw verify` | `./mvnw spotless:check` | `./mvnw spring-boot:run` |
| `integracion/` | `./mvnw -q package` | `./mvnw verify` | `./mvnw spotless:check` | `./mvnw spring-boot:run` |
| `web-profesional/` | `npm run build` | `npm test` | `npm run lint` | `npm start` |
| `app-ciudadano/` | `flutter build apk` | `flutter test` | `flutter analyze` | `flutter run` |
| `simuladores/` | — | `pytest` | `ruff check . && ruff format --check .` | `python -m generador --seed 42` |
| **Todo junto** | — | — | — | `docker compose -f infra/compose/docker-compose.yml up` |

> Las herramientas concretas de *lint* y formato (`spotless`, `ruff`) se fijan al andamiar cada
> componente; si eliges otras, **actualiza esta tabla y el workflow de CI en el mismo commit**.

## CI

Un workflow por componente en `.github/workflows/`, **todos con filtrado por `paths:`** (obligatorio
desde el primer día, §13.1 del diseño: sin él, un cambio en Flutter recompila el backend). Mientras un
componente no tenga su descriptor de construcción, su workflow se **auto-omite** con una guarda
explícita; **retira la guarda en el mismo commit** en que andamias el componente.
