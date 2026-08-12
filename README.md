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
> Pública se modelan de forma **verosímil, no fiel**: el contrato de Redalerta no es público, así que
> lo que se manda es el `Task` que publica esta misma guía. Y una diferencia que conviene decir en
> voz alta — **una declaración EDO real lleva filiación** para que Salud Pública pueda localizar al
> caso; **esta no lleva ninguna**, porque el destinatario es simulado y aquí no salen datos de
> persona hacia ningún sistema externo. Las URIs canónicas de la guía de implementación
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
- **Guía de implementación publicada:** <https://aojeda006.github.io/HispaLIS/>.

> **Hito 1 cerrado.** El circuito básico —petición → espécimen → resultado → informe— funciona de
> extremo a extremo con un `docker compose up`, con la guía FHIR propia publicada, la web del
> profesional y el generador de datos sintéticos.
>
> **Hito 2 cerrado.** El bus de eventos sobre Kafka, el motor de integración HL7 v2 sobre MLLP, el
> servidor de terminología, **la identidad con SMART on FHIR** y **la app del ciudadano**. La pila
> entera —ocho servicios— se levanta **con un solo comando**.
>
> **La API FHIR exige testigo**: lo que antes se recorría con `curl` a pelo ahora necesita uno, y el
> laboratorio decide **recurso a recurso** de quién son los datos.
>
> **Hito 3 en marcha.** Los valores críticos publicados con su fuente citada, las notificaciones de
> `SubscriptionTopic` entregando `id-only`, las pruebas reflejas con `Observation.triggeredBy`, la
> **doble validación del resultado crítico** —dos firmas de facultativos distintos— y la **detección
> de enfermedades de declaración obligatoria**, que decide sobre códigos y sin mirar quién es el
> paciente.
>
> **Y lo masivo:** `$export` de Bulk Data sobre la **cohorte de vigilancia** que el laboratorio abre
> solo al declarar, con NDJSON **seudonimizado** y un fichero que **caduca y se borra**; más un
> **`AuditEvent` de toda lectura y escritura** que registra quién, qué, cuándo y desde dónde — y ni
> una palabra más.

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
| `terminologia/` | Servidor de terminología y su cargador | HAPI FHIR + Python |
| `infra/` | Compose, Keycloak, Kafka | Docker / YAML |
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
cd ig && npx --yes fsh-sushi . && cd ..            # la guía compilada (ver ⚠️ más abajo)
cp infra/compose/.env.example infra/compose/.env   # y pon las dos contraseñas
docker compose -f infra/compose/docker-compose.yml up -d
```

Levanta **ocho servicios** —PostgreSQL, Kafka, registro de esquemas, servidor de terminología,
Keycloak, backend, motor de integración y web profesional— más tres de arranque que hacen su trabajo
y terminan: los tópicos de Kafka, la carga de terminología y el almacén de claves del MLLP. Van en
ese orden y esperando a que cada uno esté listo. La primera vez tarda unos minutos: compila el
backend y el motor con Maven y la web con Angular.

Fuera del `compose` se quedan **solo los terceros**: el HIS y el analizador simulados
(`simuladores/`), que son los sistemas del hospital y del laboratorio, no parte de HispaLIS.

El **receptor de notificaciones** es otro tercero, y va detrás de un perfil por lo mismo: tenerlo
siempre arriba daría a entender que el laboratorio depende de que esté, y no depende — si no está, la
notificación se reintenta y la suscripción se corta sola.

```bash
docker compose -f infra/compose/docker-compose.yml --profile notificaciones up -d receptor
```

Y **Salud Pública** —el servicio de declaraciones del SVEA— es el tercero de todos, con su propio
perfil. Que esté apagado en la pila normal no es comodidad: es la demostración diaria de que el
laboratorio **no depende de él**. Con el SVEA parado, un resultado declarable se valida igual y su
declaración se queda pendiente hasta que haya alguien al otro lado.

```bash
docker compose -f infra/compose/docker-compose.yml --profile edo up -d svea
curl http://localhost:8091/declaraciones   # el libro de registro: cuentas y números, ningún caso
```

⚠️ **Dos cosas viven fuera del repositorio y hay que tenerlas antes de levantar:**

1. **La guía compilada** — `npx fsh-sushi .` dentro de `ig/`. No se versiona: la produce SUSHI, y con
   ella se carga el catálogo de pruebas del servidor de terminología (D15).
2. **Las *releases* de terminología** archivadas en `BibliotecaDocumentacion`, que se asume clonada
   como carpeta hermana (`HISPALIS_RELEASES` apunta a otro sitio). **SNOMED CT Edición Española no se
   redistribuye**: hay que descargarla con la licencia propia y señalarla con `HISPALIS_SNOMED`; sin
   ella, el cargador avisa de qué conceptos se quedan sin resolver y sigue.

- **Web profesional:** `http://localhost:4200`
- **API FHIR:** `http://localhost:8080/fhir` — y también en `http://localhost:4200/fhir`, que es por
  donde entra la web: mismo origen, sin CORS.
- **Servidor de terminología:** `http://localhost:8086/fhir`
- **Kafka:** `localhost:29092` · **registro de esquemas:** `http://localhost:8085`
- **Identidad (Keycloak):** `http://localhost:8081` — realm `hispalis`, definido en
  [`infra/keycloak/`](infra/keycloak/README.md). **Hace falta un `.env`**: cópialo de
  `infra/compose/.env.example` y pon las dos contraseñas, o `docker compose` para antes de crear nada
  y dice cuál falta.
- **Motor de integración:** `localhost:2575` (MLLP sobre TLS). Su consola **no se publica**: todavía
  no tiene autenticación, y una bandeja de errores con referencias a pacientes no se abre al equipo.

### Sembrar el directorio de facultativos

Antes de que el HIS pueda mandar una petición, **los facultativos que la firman tienen que estar en
el directorio del laboratorio**. Un `OML^O21` dice quién pide en `ORC-12`, el motor lo traduce a
`ServiceRequest.requester` y, si ese facultativo no existe, la API rechaza la petición y el mensaje
acaba en la bandeja de errores del motor — que es la conducta correcta.

```bash
infra/fhir/sembrar-facultativos.sh
```

Es un guion y no un permiso más del motor **a propósito**: dejarle crear facultativos lo convertiría
en autoridad sobre un directorio que solo conoce de oídas, y un número de colegiado mal tecleado en
el HIS crearía un facultativo fantasma. Lo siembra un **profesional**, que es quien mantiene el
directorio en un laboratorio de verdad. Quién entra está en `infra/fhir/facultativos.json`, y
volver a ejecutarlo no duplica nada.

### Vincular una identidad con su historia

Antes de que la app del ciudadano pueda enseñar nada, la persona que se identifica tiene que estar
**vinculada** a su historia del laboratorio. No está en el realm a propósito: el realm es
configuración y esto es **dato**, y dato que no existe hasta que el laboratorio ha creado el
`Patient` con un id que asigna el servidor.

```bash
# 1. Que exista la historia: el HIS manda su ADT por MLLP y el motor la crea.
cd simuladores && python -m his --mensaje adt --evento A01 --nhc 70000001 && cd ..

# 2. Averigua su id (con un testigo de profesional) y vincúlala.
infra/keycloak/vincular-paciente.sh paciente.demo Patient/<id>
```

### La app del ciudadano

```bash
cd app-ciudadano
flutter pub get
flutter run -d chrome --web-port 8090   # el 8090 es el puerto registrado en el realm
```

En el emulador de Android no hace falta tocar nada: `localhost` allí es el emulador y el equipo es
`10.0.2.2`, y la app lo resuelve sola.

**La API exige testigo.** Lo que sigue siendo público es lo que tiene que serlo:

```bash
# el descubrimiento SMART: por donde empieza cualquier aplicación, antes de tener testigo
curl -s http://localhost:8080/fhir/.well-known/smart-configuration | jq

# y el CapabilityStatement, que además declara dónde se autoriza
curl -s http://localhost:8080/fhir/metadata | jq '.rest[0].security'

# todo lo demás, sin testigo, es 401 con su OperationOutcome
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/fhir/Patient   # → 401
```

**No hay atajo de consola para conseguir un testigo de profesional, y es a propósito:** el
*password grant* está deshabilitado en los dos clientes públicos, que es lo que manda SMART. Se entra
por el navegador en <http://localhost:4200> — la web lanza el flujo de autorización con PKCE — y el
testigo se puede copiar de `sessionStorage` con las herramientas del navegador si hace falta para un
`curl`. El único que se autentica sin persona es el motor de integración, con su clave
(SMART Backend Services).

**La terminología sí condiciona el arranque del laboratorio**, al revés que el bus: el laboratorio
valida códigos y resuelve `display` contra ella desde la primera escritura, y arrancar antes de que
esté cargada significaría publicar unos recursos con nombre y otros sin él. Lo que se espera no es a
que el servidor conteste, sino a que el **cargador termine**: un servidor levantado y vacío responde
`$validate-code` con «no» a todo, que es la peor forma de estar disponible.

**El bus no condiciona a la API.** Con Kafka parado, el laboratorio sigue aceptando escrituras: el
hecho se queda apuntado en el `outbox`, dentro de la misma transacción que el dominio, y el relay lo
publica cuando el broker vuelve. Si un `POST` fallara por esto, el outbox no estaría haciendo su
trabajo — que es exactamente para lo que está.

```bash
# comprobar que el servidor declara R5
curl -s http://localhost:8080/fhir/metadata | jq '.fhirVersion'   # → "5.0.0"

# los cuatro tópicos y sus esquemas registrados
curl -s http://localhost:8085/subjects | jq
curl -s http://localhost:8085/config/lab.resultados.v1-value | jq '.compatibilityLevel'  # → "BACKWARD"

# la terminología, por las cuatro operaciones estándar (nada propietario)
BASE=http://localhost:8086/fhir
IG=https://aojeda006.github.io/HispaLIS/fhir
curl -s "$BASE/ValueSet/\$expand?url=$IG/ValueSet/pruebas-del-catalogo&count=1000" | jq '.expansion.contains | length'
curl -s "$BASE/CodeSystem/\$lookup?system=$IG/CodeSystem/catalogo-pruebas&code=GLU" | jq
curl -s "$BASE/ValueSet/\$validate-code?url=$IG/ValueSet/pruebas-del-catalogo&system=$IG/CodeSystem/catalogo-pruebas&code=NOEXISTE" | jq '.parameter[0]'
curl -s "$BASE/ConceptMap/\$translate?url=$IG/ConceptMap/catalogo-a-loinc&system=$IG/CodeSystem/catalogo-pruebas&sourceCode=GLU&targetSystem=http://loinc.org" | jq

# los umbrales que obligan a avisar, con su procedencia dentro del propio concepto
curl -s "$BASE/CodeSystem/\$lookup?system=$IG/CodeSystem/catalogo-pruebas&code=K" \
  | jq '[.parameter[] | select(.name=="property") | {(.part[0].valueCode): (.part[1] | .valueDecimal // .valueString // .valueCoding.code)}] | add'

# empezar de cero (la base y el log de Kafka se conservan entre arranques en volúmenes)
docker compose -f infra/compose/docker-compose.yml down -v
```

**Recuperación.** Si alguna vez el dominio y lo publicado dejan de coincidir, la vía oficial es
`$reconciliar`, que por defecto **solo mira**. Exige `system/*.cruds` —porque **borra** recursos
publicados de cualquier tipo— y ese *scope* está definido en el realm pero **no asignado a ningún
cliente**: dárselo a alguien es un acto explícito, no una consecuencia de una plantilla.

```bash
# qué está divergente, sin tocar nada
curl -s -X POST http://localhost:8080/fhir/\$reconciliar \
  -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Parameters"}' | jq

# y arreglarlo
curl -s -X POST http://localhost:8080/fhir/\$reconciliar \
  -H 'Content-Type: application/fhir+json' \
  -d '{"resourceType":"Parameters","parameter":[{"name":"aplicar","valueBoolean":true}]}' | jq
```

**Exportación masiva (Bulk Data).** `$export` no entrega una historia: entrega **la población de una
enfermedad** en un fichero que después vive en un disco. Por eso la puerta es distinta de la de una
lectura y hacen falta **los dos permisos a la vez**, desde un cliente de sistema:

```bash
# `system/Group.rs` Y `system/*.rs`. Con uno solo, 403 — y ningún cliente del realm los trae de fábrica
curl -s -D - -o /dev/null -X POST http://localhost:8080/fhir/Group/cohorte-legionelosis/\$export \
  -H "Authorization: Bearer $TESTIGO" -H 'Prefer: respond-async'
# → 202 Accepted · Content-Location: …/fhir/$export-estado?_jobId=<uuid>

curl -s "$SONDEO" -H "Authorization: Bearer $TESTIGO" | jq   # 202 mientras trabaja, luego el manifiesto
curl -s "$URL_DEL_OUTPUT" -H "Authorization: Bearer $TESTIGO"  # el NDJSON, por un billete opaco
curl -s -X DELETE "$SONDEO" -H "Authorization: Bearer $TESTIGO"  # y borrarlo antes de que caduque
```

Cuatro cosas que conviene saber antes de usarlo, todas deliberadas:

- **La cohorte no la compone el cliente.** Es el `Group` que el laboratorio abre solo al declarar una
  enfermedad obligatoria; desde fuera es de solo lectura (`POST /fhir/Group` → `422`). Quien elige a
  los miembros elige qué se lleva.
- **Lo que sale va seudonimizado**: sexo, **año** de nacimiento y municipio. El exportador **construye
  un `Patient` nuevo** desde una lista blanca en vez de quitarle campos al original, que es la
  diferencia entre olvidarse de tapar algo y no tener por dónde colarlo. *(El municipio sale vacío
  hoy: el dominio no modela la dirección, así que la proyección no la tiene. Ver `docs/PLAN.md` →
  Notas / riesgos.)*
- **Un parámetro no soportado se rechaza con `400`.** Al revés que en una búsqueda, y a propósito:
  ignorar un `_since` devolvería más datos de los que has pedido sin decírtelo.
- **El fichero caduca (`Expires`, quince minutos), un barrendero lo borra y `DELETE` lo borra ya.**
  Vive en `HISPALIS_EXPORT_DIR` —en el `compose`, el volumen `exportaciones`— y se lo lleva un
  `docker compose down -v`.

**Y todo acceso deja traza.** Cada lectura y cada escritura de la API escriben un `AuditEvent`: quién,
qué, cuándo y desde dónde. **Referencias, nunca volcados**, y **nunca el criterio de búsqueda** — el
perfil `TrazaDeAcceso` cierra `entity.query` y `entity.detail` a `0..0`, que es justo donde acabaría
el número de historia de un `GET /fhir/Patient?identifier=…`.

```bash
# lo que alguien ha mirado de una persona
curl -s "http://localhost:8080/fhir/AuditEvent?patient=Patient/<id>" -H "Authorization: Bearer $TESTIGO" | jq

# y los accesos que NO salieron bien, que es la mitad que se investiga
curl -s "http://localhost:8080/fhir/AuditEvent?outcome:not=0" -H "Authorization: Bearer $TESTIGO" | jq
```

Quien llamó va **por identificador y no por referencia** (`agent:identifier=…|Practitioner/…`): el
`fhirUser` lo afirma el proveedor de identidad, no este servidor, y con una referencia literal la
traza de alguien que no figura en el directorio **no se podría guardar** — que es justo la que hay que
guardar (`adr-0030`).

**La base arranca vacía.** No es un descuido: la pantalla de alta de petición busca al paciente por
su número de historia y, si no consta, lo da de alta ahí mismo — que es lo que hace el mostrador de
un laboratorio privado con quien llega por primera vez. Para un corpus grande está el generador
(`simuladores/`), que hoy **escribe ficheros y no publica en la API**: cargarlo es trabajo del hito 2,
cuando exista el motor de integración.

> **En Windows no hace falta Docker Desktop.** Con WSL2 basta instalar Docker dentro de la distro
> (`sudo apt install docker.io docker-compose-v2`) y ejecutar el `compose` desde ella, con el
> repositorio en `/mnt/c/...`. Los puertos publicados se ven igual en `localhost` desde Windows.
>
> Con una salvedad que engaña: **WSL apaga la distro cuando no queda ninguna sesión abierta, y con
> ella se van los contenedores.** Si levantas la pila y cierras la terminal, se para sola; y si la
> vuelves a levantar sin comprobar qué había en pie, puedes acabar hablando con un contenedor de una
> sesión anterior —imagen vieja incluida— escuchando en el mismo puerto. Deja una sesión de WSL
> abierta mientras uses la pila, y ante cualquier resultado raro mira primero `docker compose ps`.

El estado real de cada pieza está en [`docs/PLAN.md`](docs/PLAN.md).

## Comandos por componente

| Componente | Build | Tests | Lint / formato | Arranque |
|---|---|---|---|---|
| `ig/` | `npx fsh-sushi .` · `java -jar publisher.jar -ig . -no-sushi` | validador oficial sobre `fsh-generated/resources/` | `npx fsh-sushi .` con **0 warnings** | salida en `ig/output/` |
| `backend/` | `./mvnw -q package` | `./mvnw verify` | `./mvnw spotless:check` · `./mvnw spotless:apply` | `./mvnw spring-boot:run` · sin base de datos propia: `-Parranque-local` |
| `integracion/` | `./mvnw -q package` | `./mvnw verify` | `./mvnw spotless:check` · `./mvnw spotless:apply` | `./mvnw spring-boot:run` — o el servicio `motor` del `compose` |
| `web-profesional/` | `npm run build` | `npm test` | `npm run lint` · `npm run format` | `npm start` |
| `app-ciudadano/` | `flutter build web` · `flutter build apk` | `flutter test` | `flutter analyze` (falla también con los avisos `info`) | `flutter run -d chrome --web-port 8090` |
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
