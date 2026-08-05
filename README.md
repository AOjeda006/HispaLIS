# HispaLIS

**Sistema de Información de Laboratorio (SIL) sobre HL7 FHIR R5 — simulación de un laboratorio
clínico privado en Sevilla.**

> ## ⚠️ Esto es una simulación
>
> HispaLIS **no es un producto sanitario ni un sistema en uso**. Es un proyecto de aprendizaje y
> demostración técnica. **Todos los datos son sintéticos**, generados por `simuladores/generador/`:
> nunca hay ni debe haber datos reales de pacientes, en ningún entorno.
>
> El catálogo de enfermedades de declaración obligatoria (EDO) y el formato de notificación a Salud
> Pública se modelan de forma **verosímil, no fiel**. Las URIs canónicas de la guía de implementación
> son **propias, no oficiales**. **ISO 15189 está fuera de alcance** como requisito: se cita solo como
> justificación de las decisiones de trazabilidad.

## Qué es

*Hispalis* era el nombre romano de Sevilla; **LIS** es *Laboratory Information System* — en España,
**SIL**. El proyecto simula el laboratorio de una clínica privada de tamaño medio: médicos
peticionarios, laboratorio propio y portal de resultados para el paciente. Un solo proceso, cerrado:

```
petición → extracción → espécimen → analizador → resultado → validación facultativa → informe → entrega
```

Atraviesa los ejes reales de la interoperabilidad sanitaria —una guía de implementación FHIR propia
con terminología (LOINC, UCUM, SNOMED CT Edición Española), un puente HL7 v2 sobre MLLP, un bus de
eventos, SMART on FHIR y una obligación legal española implementada (notificación EDO al SVEA)— sin
degenerar en una historia clínica electrónica en miniatura.

- **Diseño completo:** [`docs/diseno.md`](docs/diseno.md) — decisiones D1–D20, arquitectura, perfiles,
  contexto legal español. Es la fuente de verdad.
- **Estado del trabajo:** [`docs/PLAN.md`](docs/PLAN.md) — checklist, decisiones y estado actual.
- **Decisiones de arquitectura:** [`docs/adr/`](docs/adr/).
- **Guía de implementación publicada:** `https://aojeda006.github.io/HispaLIS/` (cuando el hito 1
  esté cerrado).

## Arquitectura en tres frases

1. **FHIR es un formato de borde, no el modelo de dominio.** El núcleo tiene sus propios agregados e
   invariantes; HAPI FHIR JPA es una **proyección** que se escribe en la **misma transacción**, para
   que un `GET` inmediato tras un `201` devuelva el recurso (read-your-writes es norma, no
   rendimiento).
2. **Dos planos de entrada que no se mezclan:** las aplicaciones hablan **FHIR R5 sobre HTTPS**; los
   sistemas heredados hablan **HL7 V2.5.1 sobre MLLP/TLS** y entran por el **motor de integración**,
   que traduce y escribe contra la propia API FHIR — un solo camino de escritura.
3. **La terminología es una caja obligatoria, no un `enum`:** `CodeSystem` del catálogo local,
   `ConceptMap` hacia LOINC y un servidor de terminología intercambiable.

## Estructura

| Directorio | Qué es | Tecnología |
|---|---|---|
| `ig/` | Guía de implementación: perfiles, ValueSets, ConceptMaps | FSH + SUSHI + IG Publisher |
| `backend/` | Dominio + API FHIR R5 + proyección | Java 21 + Spring Boot + HAPI FHIR |
| `integracion/` | Motor de integración, canales HL7 v2 | Spring Boot + HAPI HL7v2 |
| `web-profesional/` | Web del laboratorio | Angular |
| `app-ciudadano/` | App de resultados para el paciente | Flutter |
| `simuladores/` | Generador de datos sintéticos, HIS y analizador | Python |
| `infra/` | Compose, Keycloak, Kafka, terminología | Docker / YAML |
| `docs/` | Diseño, plan y ADR | Markdown |

Cada subproyecto tiene su propio `CLAUDE.md` con las convenciones de su stack (ver `AGENTS.md`).

## Cómo levantarlo

**Requisitos:** Docker y Docker Compose · **JDK 21** · **Node 24** (Angular 22 exige
`^22.22.3 || ^24.15.0 || >=26`) · **Python 3.11 o superior** · (Flutter, solo para `app-ciudadano/`).
SUSHI y el IG Publisher necesitan Node y JDK 21, ya cubiertos por lo anterior.

Maven **no hace falta instalarlo**: el backend trae el *wrapper* (`./mvnw`), configurado en modo
`only-script` para que el repositorio no contenga ningún binario.

```bash
git clone https://github.com/AOjeda006/HispaLIS.git
cd HispaLIS
docker compose -f infra/compose/docker-compose.yml up
```

Levanta **PostgreSQL + backend + web profesional**, en ese orden y esperando a que cada uno esté
sano. La primera vez tarda unos minutos: compila el backend con Maven y la web con Angular, y de
paso **compila la terminología de la guía con SUSHI**, porque el catálogo de pruebas que ofrece la
pantalla de alta sale del `CodeSystem` que publica la IG y no de una lista aparte (D15).

- **Web profesional:** `http://localhost:4200`
- **API FHIR:** `http://localhost:8080/fhir` — y también en `http://localhost:4200/fhir`, que es por
  donde entra la web: mismo origen, sin CORS.

```bash
# comprobar que el servidor declara R5
curl -s http://localhost:8080/fhir/metadata | jq '.fhirVersion'   # → "5.0.0"

# empezar de cero (la base se conserva entre arranques en un volumen)
docker compose -f infra/compose/docker-compose.yml down -v
```

**La base arranca vacía.** No es un descuido: la pantalla de alta de petición busca al paciente por
su número de historia y, si no consta, lo da de alta ahí mismo — que es lo que hace el mostrador de
un laboratorio privado con quien llega por primera vez. Para un corpus grande está el generador
(`simuladores/`), que hoy **escribe ficheros y no publica en la API**: cargarlo es trabajo del hito 2,
cuando exista el motor de integración.

> **En Windows no hace falta Docker Desktop.** Con WSL2 basta instalar Docker dentro de la distro
> (`sudo apt install docker.io docker-compose-v2`) y ejecutar el `compose` desde ella, con el
> repositorio en `/mnt/c/...`. Los puertos publicados se ven igual en `localhost` desde Windows.

> Mientras el hito 1 no esté cerrado, algunos de estos comandos aún no existen. El estado real de cada
> pieza está en [`docs/PLAN.md`](docs/PLAN.md).

## Comandos por componente

| Componente | Build | Tests | Lint / formato | Arranque |
|---|---|---|---|---|
| `ig/` | `npx fsh-sushi .` · `java -jar publisher.jar -ig . -no-sushi` | validador oficial sobre `fsh-generated/resources/` | `npx fsh-sushi .` con **0 warnings** | salida en `ig/output/` |
| `backend/` | `./mvnw -q package` | `./mvnw verify` | `./mvnw spotless:check` · `./mvnw spotless:apply` | `./mvnw spring-boot:run` · sin base de datos propia: `-Parranque-local` |
| `integracion/` | *(sin andamiar — hito 2)* | | | |
| `web-profesional/` | `npm run build` | `npm test` | `npm run lint` · `npm run format` | `npm start` |
| `app-ciudadano/` | *(sin andamiar — hito 2)* | | | |
| `simuladores/` | — | `pytest` | `ruff check .` · `ruff format --check .` | `python -m generador --seed 42` |

Notas de las cadenas de construcción, por si sorprenden:

- **`backend/`** — `./mvnw verify` **ya comprueba el formato**: Spotless está enganchado a la fase
  `verify`, así que no hay una orden de *lint* aparte que haya que acordarse de ejecutar.
  `./mvnw spring-boot:run` necesita un PostgreSQL en `localhost:5432`; si no tienes uno,
  **`./mvnw spring-boot:run -Parranque-local` levanta el suyo propio** —el mismo binario embebido que
  usan los tests, sin Docker y sin instalar nada—. La base de datos se crea vacía y desaparece al
  parar el proceso, así que cada arranque empieza sin pacientes.
- **`web-profesional/`** — los tests corren con **vitest sobre jsdom**, el ejecutor por defecto de
  Angular 22. **No es Karma**: `--browsers=ChromeHeadless` no es una opción válida aquí.
  `npm start` y `npm run build` traen antes el catálogo de pruebas de `ig/fsh-generated/` (D15), así
  que hay que haber ejecutado `npx fsh-sushi .` dentro de `ig/` al menos una vez; si falta, la orden
  para y lo dice. Y `npm start` levanta el servidor de desarrollo con un **proxy de `/fhir` a
  `localhost:8080`**: la web y la API se sirven del mismo origen, así que hay que tener el backend
  arrancado en otra terminal.
- **`simuladores/`** — antes de nada, `python -m pip install -e ".[dev]"` dentro de `simuladores/`.

## Integración continua

Un workflow por componente en `.github/workflows/`, **todos filtrados por `paths:`** — obligatorio en
un monorepo de cuatro *toolchains*, o cada cambio en Flutter recompilaría el backend. La IG se valida
con el **validador oficial de HL7 contra `hl7.fhir.r5.core@5.0.0`** y se publica a GitHub Pages desde
`ig/output/`.

## Desarrollo con agentes

El repo está preparado para trabajar con **Claude Code**: `CLAUDE.md` (raíz y por componente),
`AGENTS.md` (contrato operativo), `docs/PLAN.md` (estado en disco) y `PROMPT-AGENTE-LOCAL.md` (prompts
de arranque). Los `CLAUDE.md` importan las convenciones de **`BibliotecaDocumentacion`**, que debe
estar clonada como **carpeta hermana** de este repo:

```
/carpeta/HispaLIS
/carpeta/BibliotecaDocumentacion
```

Los commits van **firmados**, con la identidad del usuario y **sin ningún trailer ajeno**.

## Licencia y avisos

Proyecto personal de simulación. El material de terminología (SNOMED CT Edición Española, LOINC) está
sujeto a las licencias de sus emisores y **no se redistribuye** desde este repositorio.
