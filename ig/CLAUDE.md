# CLAUDE.md — `ig/` (guía de implementación FHIR R5)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado (principios, `interoperabilidad/fhir`
> e `interoperabilidad/espana` ya vienen de allí: **no** se repiten aquí).

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/interoperabilidad/perfilado-fsh/convenciones.md
@../../BibliotecaDocumentacion/interoperabilidad/terminologia/convenciones.md

---

## ⚠️ R5 no es R4 — la tabla que hay que mirar antes de escribir FSH

Verificado contra el paquete canónico `hl7.fhir.r5.core@5.0.0`. **Cualquier ejemplo, tutorial,
respuesta de IA o snippet basado en R4 que se copie sin mirar va a fallar aquí:**

| Elemento | R4 | **R5** | Impacto |
|---|---|---|---|
| `ServiceRequest.code` | `CodeableConcept` | **`CodeableReference`** | Cambia el JSON de *toda* petición |
| `ServiceRequest.reason` | `reasonCode` + `reasonReference` | **`reason` `0..*` `CodeableReference`** | Dos elementos fusionados en uno |
| `Coverage.kind` | no existe | **`1..1` obligatorio** (`insurance \| self-pay \| other`) | Un `Coverage` R4 **no valida** en R5 |
| `Coverage.subscriberId` | `string` | **`0..*` `Identifier`** | Cambio de tipo y cardinalidad |
| `Observation.triggeredBy` | no existe | **`0..*`** | El gancho de las pruebas reflejas |
| `Subscription.criteria` | cadena de búsqueda **dentro** de la suscripción | **no existe** → el criterio es un `SubscriptionTopic` aparte | Cambia el modelo entero, no un elemento |
| `Subscription.error` | `string` dentro del recurso | **eliminado** → `SubscriptionStatus.error` (`CodeableConcept`), por `$status` | Buscarlo y no encontrarlo invita a inventarse una extensión |
| `Group.actual` (`boolean 1..1`) | así se llama | **eliminado** → `Group.membership` (`definitional \| conceptual \| enumerated`) | Un `Group` de R4 **no valida**; `description` pasa de `string` a `markdown` |
| `AuditEvent.type` + `.subtype` | dos `Coding` | **`category` (`CodeableConcept 0..*`) + `code` (`CodeableConcept 1..1`)** | Cambia el nombre y el tipo |
| `AuditEvent.outcome` + `.outcomeDesc` | código suelto + texto | **`outcome` con `code` (`Coding`) y `detail`** | Un `AuditEvent` de R4 **no valida** en R5 |
| `AuditEvent.agent.network` | elemento con `address`/`type` | **`agent.network[x]`** (`Reference \| uri \| string`) | Y `entity.type`/`.lifecycle`/`.name`/`.description` desaparecen |
| `Observation.bodyStructure` | no existe | `0..1 Reference` | |
| `DiagnosticReport.composition` | no existe | `0..1 Reference` | |
| `Specimen.combined` / `.role` / `.feature` | no existen | nuevos | |
| `Organization.telecom` / `.address` | existen | **eliminados** → `contact` (`ExtendedContactDetail`) | Un `Organization` de R4 **no valida** en R5 |
| `ConceptMap.source[x]` / `.target[x]` · `element.target.equivalence` | así se llaman | **`sourceScope[x]`/`targetScope[x]`** y **`relationship`**, con códigos distintos | Un `ConceptMap` de R4 **no valida** en R5 |
| **Extensiones** | dentro del núcleo | **paquete aparte** `hl7.fhir.uv.extensions` | Hay que declararlo como dependencia |

---

## Reglas de esta IG

- **Versión fijada:** `hl7.fhir.r5.core@5.0.0`. Se declara en `sushi-config.yaml` y en el
  `CapabilityStatement`. Nada de "FHIR" a secas.
- **Dependencia obligatoria:** `hl7.fhir.uv.extensions` **5.3.0** (`fhirVersion 5.0.0`).
- **Base canónica:** `https://aojeda006.github.io/HispaLIS/fhir` (D19). Los `Identifier.system`
  cuelgan de `{base}/sid/…` — tabla completa en §4.8 de `../docs/diseno.md`.
- **⚠️ Antes de fijar los `system`, mirar la IG española de ÚNICAS**
  (`https://unicas-fhir.sanidad.gob.es/`). Si define URIs canónicas para DNI o CIP-SNS, **se adoptan
  en vez de inventar**. Es el **único punto del proyecto con riesgo real de retrabajo**, y toca
  hacerlo **antes** de escribir el FSH de `PacienteLabES`.
- **La fuente de verdad son los `.fsh`.** Lo que produce SUSHI (`ig/fsh-generated/`) y el IG
  Publisher (`ig/output/`, `ig/temp/`, `ig/input-cache/`) es **artefacto generado**: no se edita
  jamás y no se commitea.
- **Perfila restringiendo lo mínimo.** Un perfil sobre-restringido no se puede reutilizar. El
  *slicing* de identificadores de `PacienteLabES` se modela como jerarquía **CIP-SNS / CIP-AUT /
  NHC**, con el NUHSA como *slice* "CIP autonómico" de Andalucía — así el perfil vale para otra
  comunidad sin rehacerlo.
- **Sin `pattern` ni regex** en los identificadores que el laboratorio no emite (D16). Solo el **NHC
  propio** es `1..1` con formato validado.
- **Las extensiones de apellidos se declaran sobre el elemento `family`**, no sobre `HumanName`.
  Equivocarse aquí hace que la IG **no compile**.
- **Nada de `required` sobre conjuntos que en la práctica no están cerrados.** Es anti-patrón
  declarado.
- **Todo ejemplo valida contra su perfil en CI** con el validador oficial. Un recurso que no valida
  no sale del *pipeline*.
- **Queda escrito en la IG** que es una **simulación** con datos sintéticos, que las URIs canónicas
  son **propias y no oficiales**, y que **ISO 15189 está fuera de alcance** (D17).
- **⚠️ El idioma se declara, o el publisher asume inglés.** Están puestos `language: es`,
  `jurisdiction: ES`, `i18n-default-lang: es` y `resource-language-policy: all-ig` en
  `sushi-config.yaml`: **no los quites**. Sin ellos la guía se publica etiquetada `lang="en"` bajo
  `/en/` **con todo el build en verde** — ninguna herramienta detecta que el texto está en otro
  idioma. La salida se reparte por carpeta de idioma (`/es/`) y la raíz es un *stub* de JS que
  redirige: eso es diseño del publisher. Detalle en
  `../docs/adr/adr-0010-el-idioma-de-una-ig-se-declara-o-se-asume-ingles.md`.
- **El `id` de un `Instance:` sale del nombre del bloque, que es PascalCase.** En un artefacto de
  conformidad escrito como `Instance:` (`ConceptMap`, `CapabilityStatement`, `NamingSystem`) hay que
  poner `* id = "kebab-case"` explícito, o la página publicada y la URL canónica dirán cosas
  distintas.

## Artefactos a producir (§6.5 del diseño)

9 perfiles — `PacienteLabES`, `PeticionLab`, `EspecimenLab`, `ResultadoLab`, `InformeLab`,
`LaboratorioOrg`, `FacultativoLab`, `CoberturaLab`, `NotificacionEDO` — más la extensión propia
`codigo-ine`, el `CodeSystem` del catálogo local, el `ConceptMap` catálogo → LOINC y los `ValueSet`
de tipos de muestra, motivos de rechazo y catálogo EDO.

A eso se han ido sumando, con su ítem: `ProcedenciaValidacion`, el `SubscriptionTopic` del resultado
validado (ítem 44), los `CodeSystem` de **enfermedades EDO** y **resultados cualitativos** (ítem 47) y,
con el notificador (ítem 48), los de **estados de declaración** y **modalidades de declaración** más el
`SearchParameter` propio `notificacion-edo-vencimiento`.

### ⚠️ Un `ValueSet` se ata al `CodeableReference`, no a su `.concept`

R5 fusionó pares como `reasonCode`/`reasonReference` en un solo `CodeableReference`, y el camino que
parece natural —`* reason.concept from MiValueSet (required)`— **SUSHI lo rechaza**: *«Applying value
set bindings to a CodeableReference element's underlying .concept path is not allowed»*. Va sobre el
elemento entero (`* reason from MiValueSet (required)`), y las cardinalidades sí sobre el `.concept`.
En R4 el elemento era un `CodeableConcept` y el camino natural funcionaba, así que cualquier ejemplo
copiado de allí falla aquí.

### ⚠️ Un `SearchParameter` que el servidor tiene que ejecutar va en `active`, no en `draft`

Suena a incoherencia con el resto de la guía y no lo es. **Medido sobre HAPI 8.10.1:** el registro de
parámetros se queda **solo con los `ACTIVE`**. Uno en `draft` se guarda, se publica, se renderiza en la
guía y se lee por la API — y el servidor no lo indexa, así que la búsqueda contesta `HAPI-0524: Unknown
search parameter`. Sin error, sin aviso: el parámetro está y no funciona.

El `status` de un recurso de conformidad habla de la madurez de **la definición**, no del proyecto que
la contiene; lo que dice que esto es una simulación es `experimental = true`, que sigue puesto. Vale
igual para cualquier artefacto que el servidor **obedezca**, no solo para éste. `docs/adr/adr-0029-…`.

### Un perfil también sirve para PROHIBIR: `0..0` es una regla de verdad

`TrazaDeAcceso` cierra `AuditEvent.entity.query` y `entity.detail` a `0..0`, y no es cosmética: el
primero guarda la consulta ejecutada **en base64**, que es donde acabaría el número de historia de un
`GET [base]/Patient?identifier=…` sin que se vea al leer el recurso (`adr-0016`). Escrito así, la
regla vive en el contrato publicado y no solo en el código del servidor, y el validador oficial la
comprueba en cada ejemplo.

Es el patrón que conviene recordar: cuando la regla es «esto no puede viajar», el sitio es el perfil
con `0..0`, no un comentario ni un `if`.

### Las reglas del laboratorio viven en propiedades de `catalogo-pruebas`

Umbral crítico (43), prueba refleja (45) y declaración obligatoria (47) son **propiedades del
concepto**, no `CodeSystem` ni `ConceptMap` aparte. El motivo es el mismo cada vez: un catálogo
paralelo con los mismos códigos crea *conceptos distintos con el mismo código*, y un `$lookup` deja de
traerlo todo junto. Si añades una regla nueva, sigue el patrón — y **declara juntas las propiedades
que no valen por separado** (una refleja sin motivo, un umbral sin unidad, una EDO sin criterio).

⚠️ Y una tentación medida y descartada: R5 tiene `ConceptMap.additionalAttribute` +
`element.target.dependsOn` para condicionar un mapeo a un valor, que es exactamente lo que pide el
criterio de positividad de una EDO. **HAPI 8.10 no lo sirve** (`TranslationQuery` no tiene
`dependency`), así que publicarlo dejaría la regla en un sitio del que el backend no puede leerla.
Detalle en el comentario de `CatalogoPruebas.fsh` y en `docs/PLAN.md`.

### ⚠️ `Sistema#CODIGO` NO lleva `display`, y nada te lo dice

Escribir `* #LEGIOAG ^property[0].valueCoding = EnfermedadesEdo#LEGIONELOSIS` produce un `Coding` con
`system` y `code` **y sin nombre**. La forma con nombre lleva el literal detrás:
`EnfermedadesEdo#LEGIONELOSIS "Legionelosis"`. SUSHI compila las dos sin un aviso y el validador
oficial da cero errores en las dos, porque un `Coding` sin `display` es perfectamente válido.

Costó un fallo que solo se vio en vivo: el backend leía de ahí el nombre de la enfermedad, sacaba
`null`, y la declaración obligatoria reventaba contra un `NOT NULL` dentro de un bucle de reintentos
(`docs/adr/adr-0032-…`). **La regla, que vale para todo el FSH:** de una referencia entre conceptos se
lee la **identidad** —sistema y código—, y el contenido se le pide al dueño con su propio `$lookup`.
Poner el `display` «por si acaso» no arregla nada: duplica el nombre en dos sitios que se separan el
día que la guía renombre algo.

### ⚠️ SUSHI compila; el validador conforma. No son lo mismo

SUSHI comprueba la sintaxis FSH y que los perfiles cuadren. **No comprueba las invariantes de los
recursos que genera.** El `SearchParameter` de la guía estuvo dos días incumpliendo `spd-1` de R5
—*«if an expression is present, there SHALL be a processingMode»*— con SUSHI diciendo *0 Errors,
0 Warnings*. Lo vio el validador oficial, que es exactamente por lo que `ci-ig` lo ejecuta además.

Práctica: un artefacto de conformidad **no está comprobado** hasta que ha pasado por
`validator_cli.jar`, y eso incluye los que no son ejemplos.

## ⚠️ Trampas de la cadena de construcción — ya resueltas, no las reabras

Las cuatro salieron al andamiar la IG, **antes de que existiera un solo perfil**, y ninguna da un
mensaje que apunte a su causa. El detalle está en `../docs/adr/adr-0007-trampas-del-ig-publisher.md`.

1. **`ig.ini` se mantiene a mano y está versionado.** SUSHI **retiró la propiedad `template`** de
   `sushi-config.yaml` y ya no lo genera. Casi toda la documentación que encontrarás dice lo
   contrario: está desactualizada.
2. **`ig.ini` NO admite líneas de comentario.** Un `;` antes de `[IG]` hace que el publisher aborte
   con *«unable to find an ig.ini»* — culpando a la ausencia del fichero, que sí está. Por eso ese
   fichero no lleva comentarios explicativos y esta nota vive aquí. **Añadir un comentario "para
   aclararlo" rompe la construcción.**
3. **La plantilla es `fhir2.base.template`.** `fhir.base.template` ya no se considera segura ni está
   mantenida, y está anunciado que el publisher **se negará a ejecutarse** con ella.
4. **El publisher necesita Jekyll**, que no viene en el `.jar` ni en `ubuntu-latest`: la CI lo instala
   aparte. Sin él, la construcción recorre entera la fase FHIR y muere al renderizar las páginas.

> **Y una limitación del entorno local:** el IG Publisher **se niega a construir si hay un espacio en
> la ruta** del proyecto, y además necesita Jekyll, que en Windows no está. En la CI no ocurre.
>
> **Sí se puede construir en local, dentro de la imagen oficial** — que lleva Java y Jekyll, y en la
> que la ruta del contenedor no tiene espacios aunque la del anfitrión sí. Medido: 18 minutos, con la
> misma salida que la CI. Es lo que permite comprobar un cambio de la guía **sin empujar**:
>
> ```bash
> docker run --rm --entrypoint bash \
>   -v "$PWD/ig":/home/publisher/ig -v ~/.fhir:/home/publisher/.fhir \
>   hl7fhir/ig-publisher-base:latest \
>   -lc 'cd /home/publisher/ig && java -Xmx4g -jar publisher.jar -ig . -no-sushi'
> ```
>
> **El `qa.html` de la guía publicada es la línea base.** El publisher termina con `exit 0` aunque su
> QA cuente errores, así que «cuántos» solo significa algo comparado con
> `https://aojeda006.github.io/HispaLIS/qa.html`, que es el resultado de la última CI verde. Hoy esa
> base son **1 error** —`Supressed messages file not found`, un parámetro por defecto de la plantilla
> que apunta a un fichero que esta IG no tiene— y **488 enlaces rotos**, que son los
> `artifacts.html#terminology` y `#example` de la barra de navegación: el ancla la genera la plantilla
> en inglés y la página está en español (misma familia que `adr-0010`). Cada artefacto nuevo suma
> ~12 enlaces rotos de esos. No los cuentes como regresión sin mirar la base.

## Comandos

```bash
cd ig
npx fsh-sushi .                            # compila el FSH → fsh-generated/
java -jar publisher.jar -ig . -no-sushi    # IG Publisher → output/ (SUSHI ya ha corrido)
```

Ejemplos y perfiles se validan en CI (`.github/workflows/ci-ig.yml`) contra
`hl7.fhir.r5.core@5.0.0`. La IG se publica a GitHub Pages desde `ig/output/`.
