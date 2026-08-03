# CLAUDE.md — HispaLIS

Eres el agente encargado de desarrollar este proyecto. Trabaja según el contrato de `@AGENTS.md` y
mantén siempre actualizado el plan de registro `@docs/PLAN.md`.

> **Montaje que se asume:** `BibliotecaDocumentacion` clonada como **carpeta hermana** de este repo.
> Cada subproyecto (`ig/`, `backend/`, `integracion/`, `web-profesional/`, `app-ciudadano/`,
> `simuladores/`) tiene su **propio `CLAUDE.md`** con las convenciones de su stack; este raíz carga
> siempre y trae lo **transversal**. No dupliques imports entre ambos niveles.

---

## 1. Memoria — principios y convenciones (parte fija)

Sigue estas convenciones como **fuente de verdad** de estilo y buenas prácticas. Son normativas.

@../BibliotecaDocumentacion/principios/clean-architecture.md
@../BibliotecaDocumentacion/principios/ddd.md
@../BibliotecaDocumentacion/principios/solid.md
@../BibliotecaDocumentacion/principios/poo.md
@../BibliotecaDocumentacion/principios/programacion-funcional.md
@../BibliotecaDocumentacion/principios/naming-y-estilo.md
@../BibliotecaDocumentacion/principios/comentarios-y-documentacion.md
@../BibliotecaDocumentacion/principios/manejo-errores.md
@../BibliotecaDocumentacion/principios/testing.md
@../BibliotecaDocumentacion/principios/git-workflow.md
@../BibliotecaDocumentacion/principios/desarrollo-con-ia.md
@../BibliotecaDocumentacion/interoperabilidad/fhir/convenciones.md
@../BibliotecaDocumentacion/interoperabilidad/espana/convenciones.md
@../BibliotecaDocumentacion/herramientas/api-rest.md
@../BibliotecaDocumentacion/herramientas/seguridad.md

> Los `principios/` van aquí y no en cada componente porque los `convenciones.md` de Java,
> TypeScript, Dart y Python los declaran todos como **requisito**. Si necesitas el **porqué** de una
> convención, consulta su `referencia.md` hermano.

---

## 2. Puerta de clarificación al arrancar (parte fija)

**Antes de tocar nada**, y también al retomar tras un `/compact`:

1. Lee el objetivo del proyecto, `@docs/PLAN.md` y las convenciones importadas.
2. Identifica toda decisión **esencial** que esté **sin especificar y admita varias opciones
   viables**.
3. Si existe alguna, **pregunta todas juntas en una sola tanda** y **no empieces a trabajar** hasta
   tener respuesta. Ante la duda entre preguntar o suponer en algo esencial → **pregunta**.
4. Registra las respuestas en `docs/PLAN.md` (sección *Decisiones*) para que sobrevivan a `/compact`.

**Excepción (para no paralizarte):** lo **trivial o reversible** con un default obvio no se pregunta
— decídelo, anótalo en `PLAN.md` y sigue.

**Ya cerrado, no lo reabras:** las decisiones **D1–D20** de `docs/diseno.md` están tomadas y
justificadas. No las reabras ni propongas alternativas; si detectas un error factual, dilo en una
frase, anótalo en `PLAN.md` → *Notas / riesgos* y sigue.

---

## 3. Política de commits (parte fija)

- **Modo git de este encargo: `commit`.** Commits locales firmados. **Push solo cuando el usuario lo
  pida.** No abras PR salvo petición explícita.
- **Autor de todo commit:** `Andrés Ojeda Rodríguez <andresojedarodriguez@gmail.com>`.
- **Firma siempre.** Un commit contiene **solo las credenciales del usuario y ninguna otra**: nada de
  `Co-Authored-By`, ni de Claude ni de terceros, ni trailers de sesión. Ningún commit *unverified*.
- **Comprueba la firma antes del primer commit:** si `git config commit.gpgsign` no es `true` (falta
  el secreto `SIGNING_KEY_B64` en el entorno), **no commitees** y avisa al usuario: *"La firma no
  está activa. Añade el secreto `SIGNING_KEY_B64` con `base64 -w0 ~/.ssh/claude_signing` y reinicia
  la sesión (detalle en `plantillas/README.md` de la biblioteca)."* Espera a que lo resuelva.
- **Commits progresivos**: pequeños, atómicos, un único propósito, **Conventional Commits**
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`). Prefija el componente cuando aclare:
  `feat(ig):`, `fix(backend):`.
- **Cada commit deja el árbol coherente:** checklist de `PLAN.md` actualizado y build/tests en verde.
- **Rama por unidad de trabajo**; `main` estable.
- **Nunca** commitees secretos, `.env`, claves, `node_modules/`, `target/`, `build/`, `.venv/`,
  `ig/output/`, `ig/temp/`, `ig/input-cache/`, ni datos de pacientes.

---

## 4. Protocolo de resumabilidad (parte fija)

El estado vive **en disco**, no en el chat:

- **Al arrancar / retomar:** lee `docs/PLAN.md` y el último commit **antes de nada**. El PLAN es la
  única fuente de verdad de "qué falta"; no reconstruyas el estado de memoria.
- **Al completar cada paso:** marca el checklist de `PLAN.md`, actualiza *Estado actual* y
  *Decisiones*, y commitea. Commits pequeños = puntos de retorno.
- **Antes de terminar un turno:** deja `PLAN.md` coherente y build/tests en verde.
- **No dejes marcadores `TODO` sueltos en el código:** el trabajo pendiente vive en `PLAN.md`.

---

## 5. Trabajo concreto de este proyecto (parte variable)

- **Qué es:** **HispaLIS** — **simulación** de un **SIL** (Sistema de Información de Laboratorio) para
  un laboratorio clínico privado en Sevilla, sobre **HL7 FHIR R5**. No es un negocio real: es un
  sistema técnicamente realista y acotado que atraviesa los ejes de interoperabilidad sanitaria.
  Diseño completo y autosuficiente en `@docs/diseno.md`; **es la fuente de verdad**.
- **Stack:** Java 21 + Spring Boot + HAPI FHIR R5 · Spring Boot + HAPI HL7v2 · Angular · Flutter ·
  Python (datos sintéticos y simuladores) · PostgreSQL + MinIO + Redis · Kafka + Schema Registry ·
  Keycloak · FSH + SUSHI + IG Publisher · Docker Compose.
- **Objetivo del encargo:** completar el **hito 1** — circuito básico end-to-end
  (petición → espécimen → resultado → informe), **sin Kafka, sin HL7 v2 y sin Keycloak**. Los 12
  criterios de aceptación, convertidos en checklist, están en `@docs/PLAN.md`.
- **No-objetivos del hito 1:** motor de integración v2, Kafka/outbox, Keycloak/SMART, app Flutter,
  notificador EDO, Bulk Data, `SubscriptionTopic`. Fuera de alcance del proyecto entero: ISO 15189
  como requisito (D17), conexión al MPA de Diraya (D8), HCDSNS, Receta XXI, CMBD y ENS.
- **Cómo ejecutar y probar:** ver la tabla de comandos de `@AGENTS.md` y `@README.md`.

### 5.1. Invariantes del proyecto (no negociables)

1. **FHIR es formato de borde, no el modelo de dominio.** Nunca persistas recursos FHIR como
   entidades del dominio: el núcleo tiene sus propios agregados e invariantes (§10 del diseño).
2. **Read-your-writes.** La proyección FHIR se escribe **síncrona, en la misma transacción** que el
   dominio. Un `GET` inmediato al `Location` de un `201` **debe** devolver el recurso (§9).
3. **Un solo camino de escritura.** Todo lo que entra pasa por la API FHIR, con las mismas
   validaciones, invariantes y auditoría — incluido el motor de integración, que escribe como
   cliente `system/` (D5).
4. **La terminología es una caja obligatoria, no un `enum`.** Nada de `Map<String,String>` de códigos:
   `CodeSystem`, `ValueSet` y `ConceptMap` desde el día uno. El generador de datos sintéticos consume
   **el mismo** `CodeSystem`/`ConceptMap` que el sistema, nunca una lista paralela (D15).
5. **Nunca datos reales de pacientes**, en ningún entorno. Solo sintéticos.
6. **Nunca PHI en URLs, logs, trazas, analítica ni en el bus de eventos.** El bus publica **hechos con
   referencias** (`{ pacienteId, peticionId, observationRef }`), no volcados clínicos.
7. **TDD obligatorio** (rojo → verde → refactor) para el comportamiento de negocio.
8. **Errores en `OperationOutcome`** con el código HTTP correcto. Nunca un `200` con un error dentro.
9. **Todo en español**: documentación, doc-comments, narrativa (`text.div`), `display` y mensajes de
   usuario. Identificadores y términos técnicos estándar, en inglés cuando sea la convención del
   ecosistema (`ServiceRequest`, `accessionIdentifier`, `Bundle.link`).

### 5.2. Interoperabilidad — lo que más caro sale si se ignora

- **⚠️ R5 no es R4.** Nueve elementos cambian de tipo o de nombre; cualquier ejemplo, tutorial o
  respuesta de IA basada en R4 falla aquí. La tabla completa está en **§2.1 de `docs/diseno.md`** y
  repetida en `ig/CLAUDE.md` y `backend/CLAUDE.md`. **Míralo antes de escribir el primer recurso.**
- **URIs canónicas propias** bajo `https://aojeda006.github.io/HispaLIS/fhir` (D19, §4.8). Se
  publican en la IG y **se documenta que son propias, no oficiales**. Antes de fijarlas, mirar la IG
  española de **ÚNICAS** (primer ítem del checklist).
- **Identificadores españoles (D16):** `system` + `value` como cadena **opaca**, `0..1`,
  `Must Support`. **Sin `pattern` ni regex** en los que el laboratorio no emite (CIP-SNS, NUHSA,
  DNI/NIE, NASS). **Solo el NHC propio** lleva `1..1` y validación de formato.
- **El NUHSA nunca es `1..1`:** en un laboratorio privado, mutualistas y privados con frecuencia no
  lo conocen.
- **Apellidos dobles:** `HumanName.family` lleva el nombre familiar completo y las extensiones
  `humanname-fathers-family` / `humanname-mothers-family` lo descomponen — **declaradas sobre el
  elemento `family`**, no sobre `HumanName`. **Nunca partir por el espacio.**
- **Charset:** `MUÑOZ`, `ÁLVAREZ` y `PEÑA` son casos de prueba **obligatorios** en todo lo que toque
  nombres, no opcionales.
- **Extensiones: solo cuando no exista elemento estándar.** El diseño ya verificó que casi todo tiene
  elemento estándar en R5 (§6.1). Hay **una sola** extensión propia (código INE) y tres estándar.
  Cualquier extensión propia adicional **debe justificarse por escrito** contra §6.1 antes de crearse.

### 5.3. Aprendizajes transversales → ADR

Cuando resuelvas una cuestión reutilizable más allá de este proyecto (trampa de *toolchain*, patrón
de arquitectura, regla de portabilidad), déjala como **ADR** en `docs/adr/adr-NNNN-titulo-corto.md`
con la plantilla `patrones/plantilla-adr.md` de la biblioteca. **No edites la biblioteca a mitad de
proyecto:** primero el ADR.
