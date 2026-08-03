# HispaLIS — Diseño del sistema

> **Documento único y autosuficiente.** Contiene todo lo necesario para fabricar los artefactos de
> encargo y arrancar el desarrollo. No depende de ninguna conversación previa.
>
> **Qué es:** simulación (no es un negocio real) de un **SIL** — Sistema de Información de
> Laboratorio — para un laboratorio clínico privado en Sevilla, sobre **HL7 FHIR R5**. El objetivo es
> un sistema técnicamente realista y acotado que atraviese los ejes importantes de interoperabilidad
> sanitaria sin degenerar en una HCE en miniatura.
>
> **Estado:** v1.0 — decisiones cerradas, listo para bajar a `PLAN.md`.
> **Última revisión:** 2026-08-03

**Marcas de verificación:** `✅` verificado contra paquete canónico o repositorio oficial ·
`🔍` verificado por fuente web secundaria · `⚠️` no verificado contra fuente primaria.

---

## 1. Decisiones tomadas

| # | Decisión | Elegido | Alternativas descartadas |
|---|---|---|---|
| D1 | Versión de FHIR | **R5 (5.0.0)** | R4 (lo real hoy), R4B |
| D2 | Dominio de negocio | **Laboratorio clínico** de una clínica privada | Ensayo clínico, clínica especializada, primaria, sistema regional |
| D3 | Dónde vive FHIR | **Dominio propio + servidor HAPI como proyección** | Servidor FHIR como núcleo; fachada FHIR escrita a mano |
| D4 | Planos de entrada | **FHIR R5 por aplicaciones; HL7 v2 por sistemas** | Una sola interfaz que acepte ambos formatos |
| D5 | Camino de escritura del puente v2 | **El motor traduce v2 → FHIR y escribe contra la propia API FHIR** | El motor invoca comandos de dominio directamente |
| D6 | Ubicación y régimen | **Sevilla (Andalucía, España). Privado puro, sin concierto con el SAS** | Laboratorio concertado con el SAS |
| D7 | Terminología | **SNOMED CT Edición Española + extensión nacional del SNS · LOINC · UCUM** | SNOMED Edición Internacional (inglés) |
| D8 | Integración con la administración | **Notificación EDO a Salud Pública (SVEA). Sin conexión a Diraya** | Conectar al MPA de Diraya |
| D9 | Extensiones | **3 estándar + 1 propia.** Todo lo demás con elemento estándar (§6) | Inventar extensiones para rechazo, reflejas, ayunas, colegiado |
| D10 | Organización del código | **Monorepo único** con la IG dentro (§13) | Un repo por componente |
| D11 | Motor de integración | **Servicio Spring propio con la librería HAPI HL7v2** | Mirth Connect / OpenIntegrationEngine |
| D12 | Versión de HL7 v2 | **V2.5.1 (2007)** | V2.5 (2003) |
| D13 | App del ciudadano | **Flutter** | Jetpack Compose (solo Android) |
| D14 | Servidor de terminología | **HAPI con subconjuntos curados**, Snowstorm como intercambio documentado | Snowstorm desde el día uno |
| D15 | Datos sintéticos | **Generador propio en Python** | Synthea R4 + conversión + relocalización |
| D16 | Patrón de identificadores | **Sin `pattern`/regex en los identificadores que no emite el laboratorio** (§4.1) | Fijar el formato del CIP-SNS y el NUHSA en el perfil |
| D17 | ISO 15189 | **Fuera de alcance** como requisito; se cita como justificación de diseño (§4.4) | Tratarla como norma de obligado cumplimiento |
| **D18** | **Nombre del proyecto** | **HispaLIS** | LaboratorioFHIR, HispalisLab, SILabo |
| **D19** | **URIs canónicas** | **Bajo `https://aojeda006.github.io/HispaLIS/fhir`** (§4.8) | Dominio propio; `urn:oid:` |
| **D20** | **CLAUDE.md por componente** | **Raíz + uno por subproyecto** (§13.2) | Un solo `CLAUDE.md` raíz con todos los imports |

**No quedan cuestiones abiertas.** Lo no verificado contra fuente primaria está en §17.

### D18 — por qué *HispaLIS*

*Hispalis* era el nombre romano de Sevilla, y **LIS** es el acrónimo internacional de *Laboratory
Information System*. La mayúscula hace visible el juego. Ancla el proyecto en Sevilla sin ser un
topónimo obvio, encaja con el estilo PascalCase de los demás repos (`NexusMQ`), y **evita el choque
visual con `LaboratorioYT`** en la lista de repositorios.

**Descripción para GitHub:** *Sistema de Información de Laboratorio (SIL) sobre HL7 FHIR R5 —
simulación de un laboratorio clínico privado en Sevilla.*

---

## 2. Aviso previo sobre R5 y su coste

La convención propia fija *"R4 para producir hoy, R5 como referencia normativa"*. Una simulación es
el caso legítimo de R5, pero el coste debe estar escrito antes de empezar:

| Pierdes | Consecuencia |
|---|---|
| US Core (R4), IPS 2.0.1 (**R4**) | No hay IG existente sobre la que apoyarse |
| Synthea y la mayoría de generadores | Salida R4 → conversión (resuelto por D15: generador propio) |
| Servidores públicos de prueba y suites de conformancia | Menos red de seguridad automática |

### 2.1. Diferencias de R5 verificadas que rompen un mapeo ingenuo desde R4 ✅

Comprobadas una a una contra el paquete canónico `hl7.fhir.r5.core@5.0.0`. **Cualquier ejemplo,
tutorial, respuesta de IA o librería basada en R4 que se copie sin mirar va a fallar aquí:**

| Elemento | R4 | **R5** | Impacto |
|---|---|---|---|
| `ServiceRequest.code` | `CodeableConcept` | **`CodeableReference`** | Cambia el JSON de *toda* petición |
| `ServiceRequest.reason` | `reasonCode` + `reasonReference` | **`reason` `0..*` `CodeableReference`** | Dos elementos fusionados |
| `Coverage.kind` | no existe | **`1..1` obligatorio** (`insurance \| self-pay \| other`) | Un `Coverage` R4 **no valida** en R5 |
| `Coverage.subscriberId` | `string` | **`0..*` `Identifier`** | Cambio de tipo y cardinalidad |
| `Observation.triggeredBy` | no existe | **`0..*`** | El gancho de las reflejas |
| `Observation.bodyStructure` | no existe | `0..1 Reference` | |
| `DiagnosticReport.composition` | no existe | `0..1 Reference` | |
| `Specimen.combined` / `.role` / `.feature` | no existen | nuevos | |
| `Organization.telecom` / `.address` | existen | **eliminados** → `contact` (`ExtendedContactDetail`) | Un `Organization` de R4 **no valida** en R5 |
| `ConceptMap.source[x]` / `.target[x]` · `element.target.equivalence` | así se llaman | **`sourceScope[x]`/`targetScope[x]`** y **`relationship`**, con códigos distintos | Un `ConceptMap` de R4 **no valida** en R5 |
| **Extensiones** | dentro del núcleo | **paquete aparte** `hl7.fhir.uv.extensions` | Ver §6.2 |

> `Coverage.kind` obligatorio juega a favor: `self-pay` (privado que paga) frente a `insurance`
> (mutua o aseguradora) es **exactamente** la distinción de negocio de un laboratorio privado, y R5
> la fuerza a estar presente.

---

## 3. Por qué laboratorio clínico

| Dominio | Acotable | Riqueza FHIR | Justifica R5 | v2 + Kafka | Clientes variados | Terminología real |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| **Laboratorio clínico** | ●●● | ●●● | ●●● | ●●● | ●●● | ●●● |
| Ensayo clínico / farmacovigilancia | ●●● | ●●○ | ●●● | ●○○ | ●●○ | ●●○ |
| Clínica especializada | ●●○ | ●●● | ●●○ | ●●○ | ●●○ | ●●● |
| Centro de salud / primaria | ●○○ | ●●● | ●○○ | ●●○ | ●●● | ●●● |
| Sistema de salud de una entidad | ○○○ | ●●● | ●●○ | ●●● | ●●○ | ●●● |

**Alcance concreto:** el laboratorio de una clínica privada de tamaño medio en Sevilla, con médicos
peticionarios, laboratorio propio y portal de resultados para el paciente.

- **Un solo proceso, cerrado:** petición → extracción → espécimen → analizador → resultado →
  validación facultativa → informe → entrega. Eso es lo que impide que degenere en una HCE en
  miniatura, que es como mueren estos proyectos.
- **La terminología no es decorado:** LOINC y UCUM son el corazón del dominio.
- **El puente V2 es orgánico:** `OML^O21` y `ORU^R01` son *los* mensajes canónicos de v2.
- **R5 aporta con ganchos señalables con el dedo** (§6.6).
- **Tres tipos de cliente genuinamente distintos** con la misma API y *scopes* distintos.

---

## 4. Contexto: España · Andalucía · Sevilla (D6)

El sistema es un **SIL** — *Sistema de Información de Laboratorio*, que es como se llama en España a
lo que la literatura anglosajona llama LIS. **Usar el término local** en el dominio y la documentación.

### 4.1. Identificadores de paciente — la jerarquía real (D16)

El NUHSA y el CIP-SNS **no son identificadores paralelos**: pertenecen a una jerarquía definida por el
**RD 183/2004** 🔍, que regula la tarjeta sanitaria individual, el CIP-SNS y la Base de Datos de
Población Protegida del SNS (BDPP-SNS), modificado por el RD 702/2013 y el RD 922/2024.

| Código | Qué es | Emisor | Formato |
|---|---|---|---|
| **CIP-SNS** | Código de identificación personal del SNS. **Único y vitalicio.** Actúa de **nexo** entre los códigos autonómicos | BDPP-SNS (Ministerio de Sanidad) | **16 caracteres alfanuméricos** 🔍 |
| **CIP-AUT** | Código de identificación personal **autonómico** | Comunidad autónoma | Propio de cada CCAA |
| **CITE** | Código de la administración sanitaria **emisora** de la tarjeta | — | 11 dígitos, UNE-EN 1387:1997 🔍 |

> **El NUHSA es el CIP-AUT de Andalucía.** No es un tipo aparte. Modelarlo así en la IG (un *slice* de
> tipo "CIP autonómico" con el `system` de Andalucía) hace el perfil **reutilizable para otra
> comunidad sin rehacerlo** — que es lo que pide la convención: *"perfila restringiendo lo mínimo; un
> perfil sobre-restringido no se puede reutilizar"*.

**NUHSA** 🔍 — 12 caracteres: `AN` + 10 dígitos. Vive en la BDU de Diraya y figura de forma visible en
la tarjeta sanitaria andaluza.

| Identificador | Emisor | Ámbito | Disponibilidad en un privado |
|---|---|---|---|
| **NHC propio** | El propio laboratorio | Interno | **Siempre** — la ley lo exige (§4.4) |
| **DNI / NIE** | Ministerio del Interior | Nacional (civil) | Casi siempre |
| **NUHSA** (CIP-AUT) | SAS (BDU de Diraya) | Andalucía | **A menudo no** |
| **CIP-SNS** | BDPP-SNS | Nacional (sanitario) | Rara vez conocido |
| **NASS** | Seguridad Social | Nacional (laboral) | Ocasional |
| Póliza / nº de mutualista | Aseguradora o mutualidad | Privado | Muy frecuente → va en `Coverage` |

#### D16 — sin `pattern` en los identificadores ajenos

⚠️ La **estructura interna** del CIP-SNS (qué significa cada posición de los 16 caracteres) está en el
Anexo I del RD 183/2004, no contrastada contra fuente primaria. **No bloquea nada**, porque la
decisión correcta es no fijarla:

1. **El laboratorio no emite esos códigos.** Validar el formato de un identificador ajeno solo puede
   producir falsos rechazos de pacientes reales.
2. **Sobre-restringir es anti-patrón por convención propia:** *"`required` en bindings sobre conjuntos
   que en la práctica no están cerrados"* está en la lista de anti-patrones.
3. **La estructura puede cambiar por Real Decreto.** Ya se ha modificado tres veces (2004 → 2013 →
   2024). Un `pattern` convierte un cambio normativo en un despliegue urgente.

> **Regla:** `system` + `value` como cadena **opaca**, `0..1`, `Must Support`. **Solo el NHC propio**
> —el único que el laboratorio emite— lleva `1..1` y validación de formato.

**Consecuencia adicional para el perfil:** siendo un negocio **privado**, el NUHSA **no es universal
entre los pacientes**. Los privados y los mutualistas (MUFACE, MUGEJU, ISFAS) con frecuencia no lo
conocen — hasta el punto de que el propio SAS publica un procedimiento para que "mutualistas y
privados" averigüen su NUHSA. **Nunca `1..1`.**

### 4.2. Nombres — los dos apellidos

`HumanName.family` lleva el nombre familiar completo (`"Ojeda Rodríguez"`) y las extensiones del §6.2
lo descomponen. **Nunca partir por el espacio**: `"de la Torre Gómez"` y
`"Fernández de Córdoba Ruiz"` rompen ese heurístico, y en un laboratorio confundir apellidos es
confundir pacientes.

### 4.3. Organización, profesionales y codificación

- **`Organization.identifier` → NICA** 🔍, del Registro Andaluz de Centros, Servicios y Establecimientos
  Sanitarios (**Decreto 69/2008**). Público, consultable, aplica explícitamente a laboratorios de
  análisis clínicos. Más el **NIF**.
- **`Practitioner.identifier` → número de colegiado**, con el `system` del colegio emisor. La dirección
  de un laboratorio clínico en España la pueden ejercer médicos especialistas en Análisis Clínicos,
  **farmacéuticos**, biólogos, químicos o bioquímicos → **varios colegios emisores**, así que el
  `system` no es único: es otro *slice*. La titulación va en `Practitioner.qualification` ✅.
- **CIE-10-ES** para diagnósticos en `ServiceRequest.reason`.
- **Códigos INE** de provincia y municipio en `Address` (Sevilla = provincia 41).

### 4.4. Obligaciones legales que generan requisitos técnicos

**1. EDO — la más jugosa, y aplica de lleno a un privado.** 🔍
El **SVEA** (Decreto 66/1996) está integrado en la RENAVE nacional. La relación de EDO se fija por la
Orden de 19 de diciembre de 1996, actualizada por la **Orden de 12 de noviembre de 2015**.

- **Todos los centros sanitarios de Andalucía, públicos *y privados*, forman parte del SVEA.**
- La declaración se tramita **electrónicamente**, a la aplicación **Redalerta**.

> **Requisito técnico:** cuando un resultado se valida y su código está en el catálogo EDO, el sistema
> **debe** generar una notificación a Salud Pública. Da un consumidor de eventos con motivo legal, una
> integración saliente con una administración real, y un uso legítimo de `$export` + `Group`.

**2. Conservación de la historia clínica.** 🔍
**Ley 41/2002**: mínimo **cinco años desde el alta de cada proceso asistencial**, aplica **igual a la
sanidad privada**. Andalucía no añade plazo propio. La misma ley obliga a los centros privados no
vinculados a la red pública a **asignar un código de identificación única por paciente** — de ahí que
el NHC propio sea `1..1`.

> **Tensión que conviene modelar:** el **derecho de supresión** del RGPD **no** puede borrar lo que la
> ley obliga a conservar. Refuerza la regla de no publicar PHI en Kafka.

**3. RGPD + LOPDGDD 3/2018.** Datos de salud = categoría especial (art. 9). Base legal explícita,
minimización, registro de actividades, DPO y probable EIPD.

**4. Autorización sanitaria.** Decreto 69/2008. Sin autorización y NICA no hay laboratorio.

**5. Calidad — ISO 15189 (D17).** 🔍 La norma vigente es **UNE-EN ISO 15189:2022**, que sustituye a la
de 2012 y se realinea con la serie ISO 17000. En España acredita **ENAC**.

> **D17: fuera de alcance como requisito.** Es **acreditación voluntaria**, no obligación legal — la
> obligación es la autorización sanitaria del Decreto 69/2008. Se usa como **justificación de diseño**
> de la trazabilidad (`Provenance` de quién validó, `AuditEvent` de quién accedió) y se declara
> explícitamente fuera de alcance en la IG.

### 4.5. La relación con el SSPA — y por qué NO conectarse (D8)

Diraya tiene un **Módulo de Pruebas Analíticas (MPA)** 🔍, en marcha desde julio de 2007, con más de
**100 millones de solicitudes analíticas** procesadas. Su descripción oficial:

> *"Cada SIL mantiene su autonomía para utilizar la nomenclatura y las metodologías analíticas más
> adecuadas, pero al intercambiar información con DIRAYA, la base de datos del MPA actúa como
> traductor de los «dialectos» locales a un lenguaje común."*

Eso es, literalmente, **la arquitectura de este proyecto**: un motor de integración con `ConceptMap`
traduciendo vocabularios locales a uno común. Sirve como precedente real y citable de que el diseño no
es teórico.

**Aun así, no conectarse:** el contrato de interfaz del MPA **no es público**, y simular una
integración inventada con una administración real da falso realismo y no se puede validar. La vía EDO
ya aporta integración con la administración —real, obligatoria y documentada— y mantiene el alcance
cerrado. Se deja anotado como **destino futuro** en la IG (un `Endpoint` ✅ documentado, no
implementado).

### 4.6. Lo que NO aplica

| Elemento | Por qué queda fuera |
|---|---|
| **HCDSNS / Nodo SNS** | Nodo entre CCAA del **sector público**; un privado no se conecta |
| **Receta XXI** | Prescripción farmacéutica, no laboratorio |
| **CMBD / RAE-CMBD** | Registro de hospitalización, no de laboratorio ambulatorio |
| **ENS (RD 311/2022)** | Obliga al sector público y sus proveedores. Un privado puro no está sujeto. **Sí aplicaría con concierto** |
| **ISO 15189** | Acreditación voluntaria (D17) |

### 4.7. Idioma y juego de caracteres

- **Narrativa (`text.div`) y `display` en español** → de ahí la Edición Española de SNOMED (§5).
- **Trampa de HL7 v2:** las tildes y la `ñ` rompen tuberías v2 constantemente. Declarar el juego de
  caracteres en **`MSH-18`** y normalizar en la entrada del canal. **`MUÑOZ`, `ÁLVAREZ` y `PEÑA` son
  casos de prueba obligatorios, no opcionales.**

### 4.8. URIs canónicas (D19)

España **no tiene un juego oficial consolidado** de URIs canónicas para estos `system`. Se definen
propias, se publican en la IG y **se documenta que son propias, no oficiales**.

**Base canónica de la IG:** `https://aojeda006.github.io/HispaLIS/fhir`

| Artefacto | URI |
|---|---|
| Perfiles | `{base}/StructureDefinition/{nombre}` |
| Extensión propia INE | `{base}/StructureDefinition/codigo-ine` |
| CodeSystem catálogo local | `{base}/CodeSystem/catalogo-pruebas` |
| ConceptMap catálogo → LOINC | `{base}/ConceptMap/catalogo-a-loinc` |
| ValueSets | `{base}/ValueSet/{nombre}` |

**Espacios de nombres de identificador** (`Identifier.system`):

| Identificador | `system` |
|---|---|
| NHC propio | `https://aojeda006.github.io/HispaLIS/sid/nhc` |
| DNI / NIE | `https://aojeda006.github.io/HispaLIS/sid/dni-nie` |
| NUHSA (CIP-AUT Andalucía) | `https://aojeda006.github.io/HispaLIS/sid/nuhsa` |
| CIP-SNS | `https://aojeda006.github.io/HispaLIS/sid/cip-sns` |
| NASS | `https://aojeda006.github.io/HispaLIS/sid/nass` |
| NICA | `https://aojeda006.github.io/HispaLIS/sid/nica` |
| NIF | `https://aojeda006.github.io/HispaLIS/sid/nif` |
| Nº de colegiado | `https://aojeda006.github.io/HispaLIS/sid/colegiado/{colegio}` |

> ⚠️ **Antes de fijar estos `system` en la IG, mirar la IG española de ÚNICAS**
> (`https://unicas-fhir.sanidad.gob.es/`). Si define URIs canónicas para DNI o CIP-SNS, **adoptarlas
> en vez de inventar** — es el único punto del diseño con riesgo real de retrabajo. Es una revisión de
> diez minutos, y toca hacerla **antes** de escribir el FSH de `PacienteLabES`.

---

## 5. Terminología (D7, D14)

- **SNOMED CT** 🔍 — España es miembro desde **2009**. El **Centro Nacional de Referencia** es el
  **Ministerio de Sanidad**, único distribuidor en territorio español. Se publican la **Edición
  Internacional en Español** más **dos extensiones nacionales** (SNS y Medicamentos). **Licencia
  gratuita** en territorio español, previo registro.
  > Cargar la **Edición Española** y **declarar la versión exacta del *release***. Los `display` en
  > inglés en un informe de laboratorio español son un error de producto, no un detalle.
- **LOINC** — `Observation.code` y `ServiceRequest.code`. **LOINC 2.82 ya archivado en la biblioteca** ✅.
- **UCUM** — unidades. Obligatorio en `Quantity.system`.
- **Catálogo local del laboratorio** — el "dialecto" del que habla el MPA. `CodeSystem` propio +
  `ConceptMap` hacia LOINC. **Este es el eje de terminología del proyecto.**

### D14 — servidor de terminología: HAPI con subconjuntos curados

**Elegido:** el servidor de terminología de **HAPI**, como servicio aparte, cargado con LOINC 2.82,
THO 7.3.0 (ambos ya archivados ✅) y subconjuntos curados de SNOMED español, más el `CodeSystem` del
catálogo local y los `ConceptMap`.

**Por qué no Snowstorm de entrada:** es el servidor oficial de SNOMED International y la opción
correcta a escala, pero exige Elasticsearch y varios GB de RAM para la edición completa. En un proyecto
de una persona consume presupuesto de esfuerzo sin enseñar nada nuevo.

**El argumento decisivo:** la lección del proyecto es el **binding**, el contrato
`$expand`/`$validate-code`/`$translate` y el `ConceptMap` del catálogo local — no operar Snowstorm. Y
precisamente porque la terminología se resuelve **contra la API estándar**, el servidor es
**intercambiable**: migrar a Snowstorm después es cambiar una URL. Empezar ligero es gratis; empezar
pesado, no. Se documenta el intercambio en un ADR.

---

## 6. Perfiles y extensiones (D9)

**Regla que gobierna esta sección** (convención propia): *"Extensiones: solo cuando no exista elemento
estándar."* Aplicada literalmente, verificando cada caso contra el paquete canónico. **Resultado: casi
todo lo que parecía necesitar extensión ya tiene elemento estándar en R5.**

### 6.1. Lo que NO necesita extensión — verificado ✅

| Necesidad de negocio | Elemento estándar R5 | Detalle verificado |
|---|---|---|
| **Rechazo de muestra** | `Specimen.status` + `Specimen.condition` | `status` admite **`unsatisfactory`** (`available \| unavailable \| unsatisfactory \| entered-in-error`); `condition` es `0..* CodeableConcept` |
| **Prueba refleja** | `Observation.triggeredBy` | `.observation` `1..1 Reference`, `.type` `1..1 code` (**`reflex \| repeat \| re-run`**), `.reason` `0..1 string` |
| **Paciente en ayunas** | `Specimen.collection.fastingStatus[x]` | `CodeableConcept \| Duration`, *binding* a v2-0916 |
| **Rango por sexo y edad** | `Observation.referenceRange.appliesTo` + `.age` | `appliesTo` `0..* CodeableConcept`; `.age` es `Range` |
| **Nº de colegiado y titulación** | `Practitioner.identifier` + `.qualification` | Un *slice* por colegio emisor |
| **NICA del laboratorio** | `Organization.identifier` | Más el NIF como segundo *slice* |
| **Privado que paga vs mutua** | `Coverage.kind` | **`1..1` obligatorio en R5** (`insurance \| self-pay \| other`) |
| **Nº de petición que agrupa líneas** | `ServiceRequest.requisition` | `Identifier` |
| **Nº de acceso de la muestra** | `Specimen.accessionIdentifier` | `Identifier` |
| **Quién validó el resultado** | `Provenance` | Recurso aparte |
| **Notificación EDO** | `Task` | `.focus` → `Observation`, `.for` → `Patient`, `.status`, `.businessStatus`, `.owner` |
| **Destino futuro (MPA)** | `Endpoint` | Documentado, no implementado |

> **Cero extensiones inventadas** para la lógica del laboratorio.

### 6.2. Extensiones estándar necesarias — verificadas ✅

Comprobadas contra el repositorio oficial **`HL7/fhir-extensions`**, paquete
**`hl7.fhir.uv.extensions` versión 5.3.0**, `fhirVersion 5.0.0` — el que hay que **declarar como
dependencia** en `sushi-config.yaml`.

| Extensión | URL canónica | Contexto | Tipo | Estado |
|---|---|---|---|---|
| **Fathers Family** | `http://hl7.org/fhir/StructureDefinition/humanname-fathers-family` | **`HumanName.family`** | `string` | active, FMM 3 |
| **Mothers Family** | `http://hl7.org/fhir/StructureDefinition/humanname-mothers-family` | **`HumanName.family`** | `string` | active, FMM 3 |
| **Data Absent Reason** | `http://hl7.org/fhir/StructureDefinition/data-absent-reason` | `Element` | `code` | active, FMM 3 |

> **Precisión crítica:** el contexto de las extensiones de apellidos es **`HumanName.family`**, no
> `HumanName`. En FSH se declaran **sobre el elemento `family`**, no sobre el nombre. Equivocarse aquí
> hace que la IG no compile.

`data-absent-reason` entra porque la convención propia lo exige: *"si la ausencia es información, usa
`dataAbsentReason` — nunca un valor vacío"*. En `Observation` es elemento del núcleo, pero para el
resto de elementos (un DNI ausente, por ejemplo) hace falta la extensión.

### 6.3. Extensiones estándar disponibles pero fuera de alcance — verificadas ✅

| Extensión | URL canónica | Estado | Por qué queda fuera |
|---|---|---|---|
| Patient Nationality | `…/patient-nationality` | active, FMM 3, compleja | Existe y es madura, pero el dominio del laboratorio no la pide |
| Patient Birth Place | `…/patient-birthPlace` | active, FMM 3, `Address` | Ídem |
| Patient Citizenship | `…/patient-citizenship` | active, FMM 3, compleja | Alternativa a nationality; tampoco la pide el dominio |
| Individual Gender Identity | `…/individual-genderIdentity` | active, **FMM 2**, compleja | `Patient.gender` es **sexo administrativo**; correcto modelarlo aparte, pero no lo necesita el hito 1 |
| Sex Parameter for Clinical Use | `…/patient-sexParameterForClinicalUse` | **draft, FMM 1** | Mecanismo canónico para elegir rango de referencia, muy pertinente — pero **está en borrador**. Construir sobre un `draft` FMM 1 es deuda garantizada. `referenceRange.appliesTo` cubre el caso |

### 6.4. Extensiones propias — solo una

| Extensión | URI | Motivo |
|---|---|---|
| **Código INE** de municipio/provincia sobre `Address` | `{base}/StructureDefinition/codigo-ine` | No existe elemento ni extensión estándar |

Cualquier extensión propia adicional **debe justificarse por escrito** contra la tabla §6.1 antes de
crearse.

### 6.5. Perfiles a escribir (FSH)

| Perfil | Base | Lo que restringe |
|---|---|---|
| `PacienteLabES` | `Patient` | *Slicing* de `identifier` con la jerarquía CIP-SNS / CIP-AUT / NHC (§4.1), **sin `pattern`** (D16) · apellidos (§6.2) · INE en `address` · `name` `1..*` |
| `PeticionLab` | `ServiceRequest` | `code` LOINC (**`CodeableReference`** ✅) · `requisition` · `specimen` · `reason` CIE-10-ES |
| `EspecimenLab` | `Specimen` | `type` SNOMED · `condition` para rechazo · `collection.fastingStatus` · `accessionIdentifier` `1..1` |
| `ResultadoLab` | `Observation` | `code` LOINC `1..1` · `value[x]` con UCUM · `referenceRange` · `triggeredBy` · `specimen` `1..1` |
| `InformeLab` | `DiagnosticReport` | `code` LOINC · `result` `1..*` · `performer` · `presentedForm` (PDF) |
| `LaboratorioOrg` | `Organization` | `identifier` NICA + NIF |
| `FacultativoLab` | `Practitioner` | `identifier` nº colegiado por colegio · `qualification` |
| `CoberturaLab` | `Coverage` | `kind` (`self-pay` vs `insurance`) · `insurer` · `subscriberId` |
| `NotificacionEDO` | `Task` | `code` = notificar EDO · `focus` → `ResultadoLab` · `businessStatus` |

Más los artefactos de terminología: `CodeSystem` del catálogo local, `ConceptMap` catálogo → LOINC, y
los `ValueSet` de tipos de muestra, motivos de rechazo y catálogo EDO.

### 6.6. Los ganchos R5, ya concretos ✅

- **`Observation.triggeredBy`** — El `ValueSet` `reflex | repeat | re-run` describe **exactamente** los
  tres casos de un laboratorio: la refleja (TSH alterado → T4 libre), la repetición de control y la
  re-ejecución por fallo técnico.
- **`SubscriptionTopic` + `Subscription`** — Tópico `resultado-validado`. En R4 es un apaño sobre
  `criteria`; en R5 es un recurso de primera clase.
- **`$export` + `Group`** — Cohorte para vigilancia epidemiológica, con **motivo legal real** (§4.4).
- **`Coverage.kind` obligatorio** — R5 fuerza `self-pay` vs `insurance`.

---

## 7. Los dos planos de entrada (D4)

**HL7 v2 no entra por el front.** Un navegador o una app móvil no hablan MLLP — v2 es un protocolo de
socket con *framing* propio, no HTTP.

| | Plano de **aplicaciones** | Plano de **sistemas** |
|---|---|---|
| Quién | Web profesional, app ciudadano, kiosco, terceros | HIS de la clínica, analizadores |
| Formato | **FHIR R5, solo** | **HL7 V2.5.1, solo** |
| Transporte | HTTPS | MLLP sobre TLS |
| Autorización | SMART on FHIR (`patient/`, `user/`, `system/`) | Credenciales del canal + red confinada |
| Entra por | API FHIR | **Motor de integración** |

**Por qué separados:** *"no mezcles versiones en una misma interfaz; si hay que convertir, hazlo en un
punto explícito y documentado"*. El motor **es** ese punto. Si v2 llega a la API FHIR, se han fundido
dos contratos en una puerta y el mapeo deja de poder auditarse — el fallo que el motor existe para
evitar.

### D5 — el motor escribe contra la propia API FHIR

El motor traduce v2 → FHIR R5 y **escribe contra la propia API FHIR**, autenticándose como cliente
`system/` vía SMART Backend Services. **Un solo camino de escritura**, con las mismas validaciones,
invariantes y auditoría que cualquier otro cliente. Matices: agrupar con bundles `transaction` si hay
cuello de botella · deduplicar por `MSH-10` **en el motor, antes de escribir** · charset en `MSH-18`.

### D12 — versión de HL7 v2: **V2.5.1**

Decidido con **evidencia medida**, no de memoria. La biblioteca archiva V2.5 (2003) y V2.5.1 (2007)
completas, y su `_fuente/INDICE.md` registra el diff medido ✅:

> *"El diff entre V2.5 y V2.5.1 podría haberse resumido de memoria como «V2.5.1 es la versión de
> laboratorio». Medido, es otra cosa: **mismo conjunto de 151 segmentos, mismo conjunto de 344 tablas,
> mismo `MSH` de 21 campos**, y un diff dominado por ejemplos saneados y erratas — exactamente el
> catálogo de correcciones técnicas que el propio estándar define en su sección 2.8.6."*

**Conclusión:** V2.5.1 es V2.5 con las erratas corregidas. Elegirla **no cuesta nada**.
⚠️ **Salvedad registrada en la biblioteca: la tabla 0354 tiene contenido distinto entre ambas
versiones.** Cruzarla antes de generar código; no asumir equivalencia. **Ambas versiones están
archivadas**, así que el cruce se hace en local.

### D11 — motor de integración: servicio Spring propio con HAPI HL7v2

**Elegido:** un servicio Spring Boot propio usando la librería **HAPI HL7v2** (`ca.uhn.hl7v2`, la
librería Java de referencia para v2, proyecto hermano de HAPI FHIR), que aporta el listener MLLP, el
parser y la generación de acuses.

**Por qué no Mirth/OIE:**

1. **Mismo lenguaje y misma cadena de construcción que el backend** → encaja en el monorepo y en la CI
   sin un runtime aparte con su propio modelo de despliegue.
2. **Canales como código por construcción.** La convención exige *"despliegue de canales por el mismo
   circuito que el código, no editando en la consola de producción"*. Con Mirth eso es una disciplina
   que hay que imponerse **contra** la herramienta; con un servicio propio es lo único posible.
3. **El trabajo que ahorra Mirth no es el que enseña.** HAPI HL7v2 ya da parser, MLLP y acuses — lo que
   se escribe es la lógica del canal (filtro, transformación, enrutado), que es la parte que interesa.
4. Mirth brilla operando decenas de interfaces con equipos de integración; no es este caso.

**Coste asumido:** se pierde el almacén de mensajes, los reintentos y la consola de reproceso que Mirth
trae hechos. Hay que construirlos: guardar el mensaje original íntegro, DLQ y un punto de reproceso
idempotente. Es trabajo del hito 2 — y es lo que las convenciones de integración exigen de todos modos.

### 7.1. ⚠️ MLLP — la trampa documental tiene TRES capas

La biblioteca registra la primera. Las otras dos son hallazgos de este análisis y **deben incorporarse
a `interoperabilidad/hl7-v2/`**:

1. ✅ *(registrado)* El **apéndice B** (*Lower Layer Protocols*) de V2.5 y V2.5.1 **está vacío**: una
   página que dice que el contenido se movió a la *Implementation Guide*.
2. ❌ *(sin registrar)* **Ese documento no es una guía de V2: es un estándar de HL7 Version 3.** Se
   titula **«HL7 Version 3 Standard: Transport Specification — MLLP, Release 2»** 🔍. El protocolo que
   transporta prácticamente todo el tráfico V2 del mundo está publicado bajo el paraguas de **V3**.
3. ❌ *(sin registrar)* **Está RETIRADO desde 2025.** HL7 publicó la retirada normativa el **25 de
   junio de 2025**, con estado *retired* desde el **16 de mayo de 2025** («Withdrawal of V3: Transport
   Specification - MLLP, R2») 🔍. **No hay sustituto designado.**

> **Consecuencia real: hoy no existe un estándar HL7 vigente para MLLP**, mientras MLLP sigue siendo
> el transporte universal de V2 en producción. Es una anomalía que merece entrar en la biblioteca tal
> cual — es exactamente el tipo de trampa que existe para registrar.

**Impacto en el proyecto: ninguno.** HAPI HL7v2 implementa el *framing* (`0x0B` … `0x1C 0x0D`); nunca
se escribe a mano. Lo que falta es **fuente citable**, no código. Ver §17 para las descargas.

---

## 8. Esquema general

```
┌───────────────────────── CLIENTES ──────────────────────────────┐
│ Web profesional      App ciudadano       Puesto de extracción   │
│ (Angular)            (Flutter)           (web, etiquetado)      │
│ SMART EHR launch     SMART standalone                           │
│ user/*.rs            + PKCE, público                            │
│                      patient/*.rs                               │
│                                                                 │
│ ── clientes NO humanos ─────────────────────────────────────────│
│ Mutua / aseguradora · Salud Pública (EDO)                       │
│ SMART Backend Services  system/*.r                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTPS  ── PLANO DE APLICACIONES
┌──────────────────────────────▼──────────────────────────────────┐
│ BORDE — API Gateway                                             │
│ TLS · rate limit · correlation-id · saneado de PHI en los logs  │
│ valida el JWT y enruta. NO entiende de FHIR.                    │
└────────┬─────────────────────────────────────────────┬──────────┘
         │                                             │
┌────────▼───────────────┐                   ┌─────────▼──────────┐
│ IDENTIDAD  (Keycloak)  │                   │ API FHIR R5        │
│ OAuth2/OIDC + SMART v2 │◄─ introspección ──│ (Spring + HAPI)    │
│ scopes patient/user/   │                   │ /metadata          │
│   system · launch ctx  │                   │ REST + búsqueda    │
│ .well-known/           │                   │ $validate $export  │
│   smart-configuration  │                   │ Subscription (R5)  │
│ JWKS (backend svcs)    │                   │ interceptores:     │
└────────────────────────┘                   │ authz·consent·     │
              ▲                              │ audit·ETag/If-Match│
              │                              └───┬───────────┬────┘
              │           ┌──────────────────────▼──┐   ┌────▼─────────────┐
              │           │ NÚCLEO DE DOMINIO       │   │ TERMINOLOGÍA     │
              │           │ casos de uso            │──►│ HAPI + subconj.  │
              │           │ invariantes de negocio  │   │ LOINC 2.82 · THO │
              │           │                         │   │ SNOMED ed. ESP   │
              │           │ FHIR es formato de      │   │ $expand $lookup  │
              │           │ BORDE, no el modelo     │   │ $validate-code   │
              │           └────┬──────────────┬─────┘   │ $translate       │
              │                │              │         └──────────────────┘
              │  ┌─────────────▼──────┐   ┌───▼─────────────────────────────┐
              │  │ DATOS              │   │ BUS DE EVENTOS (Kafka)          │
              │  │ PostgreSQL         │   │ tópico por dominio de evento    │
              │  │  · dominio (SoT)   │   │ clave de partición = paciente   │
              │  │  · HAPI JPA (read) │   │ Schema Registry, compat. atrás  │
              │  │ MinIO/S3           │   │ publica el HECHO, no la orden   │
              │  │  · informes, NDJSON│   └──┬───────┬────────┬─────┬───────┘
              │  │ Redis              │      │       │        │     │
              │  │  · caché term.     │  proyector notific. analít. NOTIFICADOR
              │  │  · dedup / idemp.  │   FHIR   (Subscr.) (Bulk)   EDO
              │  └────────────────────┘                              │
              │                                                      ▼
              │ escribe como cliente system/            ┌────────────────────┐
              │                                         │ SALUD PÚBLICA      │
┌─────────────┴────────────────────────────────────┐    │ SVEA / Redalerta   │
│ MOTOR DE INTEGRACIÓN                             │    │ (obligación legal, │
│ Spring Boot + HAPI HL7v2  (D11)                  │    │  también privados) │
│ canal: origen → filtro → transformador → destino │    └────────────────────┘
│ guarda el ORIGINAL íntegro · DLQ · reproceso     │
│ metadatos indexables: paciente, episodio, MSH-10 │
│ dedup por MSH-10 · charset MSH-18                │
└──────────────┬───────────────────────────────────┘
               │ MLLP sobre TLS  ── PLANO DE SISTEMAS
      ┌────────▼────────────────────────────────────┐
      │ SISTEMAS HEREDADOS (simulados, internos)    │
      │ HIS clínica → ADT^A01/A08 (demografía)      │
      │ HIS clínica → OML^O21 (petición)            │
      │ Analizador  → ORU^R01 (resultado)           │
      │            HL7 V2.5.1  (D12)                │
      └─────────────────────────────────────────────┘

      [FUTURO, no implementado: MPA de Diraya (SSPA) — ver §4.5]
```

### Notas de diseño que no se ven en el dibujo

- **FHIR es un formato de borde, no el modelo de dominio.** El error clásico es persistir recursos FHIR
  como si fueran entidades. Son un contrato de intercambio: opcionalidad enorme, `[0..*]` por todas
  partes, sin los invariantes del negocio.
- **La terminología es una caja obligatoria, no un `enum`.** Si no se pone desde el día uno, aparece un
  `Map<String,String>` y ya no sale.
- **El bus no es la historia clínica.** Hechos con referencias, no volcados de PHI.
- **El gateway no habla FHIR.** Valida el token y enruta. En cuanto se le mete lógica FHIR hay dos
  servidores FHIR y ninguno conforme.
- **Un scope concedido no garantiza los datos.** El consentimiento se aplica en el servidor FHIR.

---

## 9. El fork estructural (D3) y su trampa

**FHIR REST exige leer-lo-que-acabas-de-escribir.** Se devuelve `201 Created` con
`Location: Observation/123`; si la proyección es asíncrona, el `GET` inmediato da `404`. No es un
detalle de rendimiento: **es incumplir la norma**.

```
ESCRITURA
cliente ──POST/PUT FHIR──► [API FHIR · ResourceProvider de escritura]
                                    │ traduce el recurso a un COMANDO
                                    ▼
                          [NÚCLEO DE DOMINIO]
                          valida los invariantes de negocio
                                    │
                    ┌───────────────┴── UNA sola transacción PG ──┐
                    ▼                   ▼                         ▼
            esquema `dominio`    esquema `fhir` (HAPI JPA)     `outbox`
            fuente de verdad     proyección + índices de       hechos a
                                 búsqueda (vía IFhirResourceDao) publicar
                                    │
                     ◄── 201 + Location + ETag (W/"1") ──

LECTURA                      (cero mapeo en tiempo de lectura)
cliente ──GET / search──► [DAOs HAPI JPA] ──► esquema `fhir`
                          búsqueda · _include · paginación · $export ·
                          history · Subscriptions

HECHOS                       (fuera del camino de lectura)
outbox ──relay──► Kafka (clave = pacienteId) ──► notificaciones ·
                  notificador EDO · analítica · DWH
```

**La clave:** la proyección FHIR se escribe **síncrona, en la misma transacción** que el dominio (mismo
PostgreSQL, esquemas distintos, un solo `@Transactional`; la proyección llama a las DAOs de HAPI para
que se pueblen los índices de búsqueda). Kafka **no** alimenta el modelo de lectura — alimenta a todo
lo demás, vía **outbox transaccional** para no perder hechos si el broker está caído.

**Lo que HAPI JPA da gratis:** búsqueda por `SearchParameter`, `_include`/`_revinclude`, paginación por
`Bundle.link`, `_history`, `ETag`/`versionId`, `$validate`, `$export` y Subscriptions.

---

## 10. Los dos modelos: el propio y el que se publica

**No coinciden, y no deben.** Es la razón de ser de la arquitectura elegida.

| Agregado (dominio) | Contenido | Invariantes que FHIR no puede expresar |
|---|---|---|
| **Petición** | paciente, peticionario, líneas, estado | No se cierra con líneas pendientes |
| **Espécimen** | tipo, extracción, contenedor, estado, motivo de rechazo | **Rechazado ⇒ no puede producir resultado** |
| **Resultado** | prueba, valor + unidad, rango, interpretación, validador | Crítico ⇒ **doble validación** y notificación obligatoria |
| **Informe** | petición, resultados, estado, PDF | Solo se emite con todas las líneas resueltas |
| **Notificación EDO** | resultado, enfermedad, fecha, estado de envío | Resultado EDO validado ⇒ notificación obligatoria |

La proyección FHIR está en §6.5 (perfiles) y §6.1 (mapeo de elementos).

---

## 11. Contratos de integración

**HL7 V2.5.1 entrante** (MLLP sobre TLS):

| Mensaje | Produce |
|---|---|
| `ADT^A01` / `A08` | `Patient` (demografía, altas y correcciones) |
| `OML^O21` | `ServiceRequest` + `Specimen` |
| `ORU^R01` | `Observation` (resultado bruto del analizador) |

**Saliente:** `ORU^R01` hacia el HIS cuando el informe se valida · notificación EDO a SVEA/Redalerta
cuando un resultado validado cae en el catálogo de declaración obligatoria.

**Tópicos Kafka** (clave = `pacienteId`, esquema versionado, compatible hacia atrás):

```
lab.peticiones.v1    lab.especimenes.v1    lab.resultados.v1    lab.informes.v1
```

Publican **hechos con referencias**: `{ pacienteId, peticionId, observationRef }`.

---

## 12. Stack

| Capa | Elección | Por qué |
|---|---|---|
| API FHIR + dominio | **Java 21 + Spring Boot + HAPI FHIR R5** | Implementación de referencia |
| Motor de integración | **Spring Boot + HAPI HL7v2** (D11) | Mismo toolchain; canales como código |
| Web profesional | **Angular** | Convenciones ya disponibles |
| App ciudadano | **Flutter** (D13) | iOS + Android desde un código |
| Identidad | **Keycloak** | OIDC estándar; SMART encima con mappers |
| Eventos | **Kafka + Schema Registry** | |
| Datos | **PostgreSQL + MinIO + Redis** | |
| Terminología | **HAPI + subconjuntos** (D14) | LOINC 2.82 y THO 7.3.0 ya archivados |
| Datos sintéticos | **Python + Faker `es_ES`** (D15) | |
| Perfilado | **FSH + SUSHI + IG Publisher** | Dependencia `hl7.fhir.uv.extensions@5.3.0` |
| CI | Validador oficial contra `hl7.fhir.r5.core@5.0.0` | Un recurso que no valida no sale del pipeline |

### D13 — app del ciudadano: Flutter

El objetivo declarado era **clientes multiplataforma**. Jetpack Compose es solo Android; en España iOS
es aproximadamente la mitad del mercado, así que una app de resultados que solo funcione en Android no
cumple la premisa. El coste — una cadena de construcción más (Dart) en el monorepo — está contabilizado
en §13.

### D15 — datos sintéticos: generador propio

**Por qué no Synthea:** apilaría **dos problemas difíciles** — convertir R4 → R5 y relocalizar de
EE. UU. a España (nombres, **apellidos dobles**, direcciones de Sevilla, DNI/NIE con dígito de control
válido, NUHSA con su formato).

**El argumento decisivo:** lo difícil de unos datos de laboratorio realistas **no es la demografía, son
los resultados clínicamente verosímiles** — paneles correlacionados (un hemograma cuyos campos cuadren
entre sí), valores dentro y fuera de rango, y disparos de reflejas que ejerciten `triggeredBy`. El
generador propio, además, **hace doble función como arnés de carga y de pruebas**.

**Disciplina obligatoria:** el generador consume **el mismo `CodeSystem` y `ConceptMap`** que el
sistema, nunca una lista paralela. Si no, genera datos que solo valen para sí mismo.

---

## 13. Organización del código (D10, D20)

| # | Componente | Tecnología |
|---|---|---|
| 1 | **IG** — perfiles, ValueSets, ConceptMaps | FSH + SUSHI + IG Publisher |
| 2 | **Backend / SIL** — dominio + API FHIR + proyección | Java 21 + Spring Boot + HAPI FHIR |
| 3 | **Motor de integración** — canales v2 | Spring Boot + HAPI HL7v2 |
| 4 | **Web profesional** | Angular |
| 5 | **App ciudadano** | Flutter |
| 6 | **Notificador EDO** — consumidor Kafka | Java (puede vivir dentro de 2) |
| 7 | **Simuladores** — HIS y analizador, generador de datos | Python |
| 8 | **Infraestructura** — compose, realm Keycloak, tópicos, terminología | Docker / YAML |

### 13.1. Estructura del monorepo

```
HispaLIS/
├── CLAUDE.md            # raíz: parte fija + invariantes de proyecto + interoperabilidad
├── AGENTS.md
├── README.md
├── .gitignore
├── .claude/settings.json
├── .github/workflows/   # CI con filtrado por `paths:`
├── docs/
│   ├── PLAN.md          # la spec viva
│   ├── diseno.md        # este documento
│   └── adr/             # ADR-0001…0006
├── ig/                  # CLAUDE.md propio · FSH → GitHub Pages
├── backend/             # CLAUDE.md propio · dominio + API FHIR + proyección
├── integracion/         # CLAUDE.md propio · canales v2
├── web-profesional/     # CLAUDE.md propio · Angular
├── app-ciudadano/       # CLAUDE.md propio · Flutter
├── simuladores/         # CLAUDE.md propio · Python
└── infra/               # compose, Keycloak, Kafka, terminología
```

**Por qué monorepo:**

1. **El contrato es compartido y cambia a la vez.** La IG define los perfiles que el backend valida, la
   web consume y el motor produce. En repos separados, tocar un perfil obliga a un baile de versiones a
   tres bandas; en monorepo es **un commit atómico que pasa o rompe CI de golpe**. Argumento decisivo:
   el acoplamiento por contrato es real, no accidental.
2. **Trabajo en solitario.** Coordinar 8 repos es un impuesto que solo se paga con equipos separados.
3. **La demo end-to-end** (`docker compose up`) es trivial en monorepo.
4. **Un solo `PLAN.md` y un solo historial** — encaja con el protocolo de resumabilidad.

**Coste asumido:** el monorepo mezcla *toolchains* de Java, TypeScript, Dart y Python. La CI **debe**
filtrar por ruta (`paths:` en GitHub Actions) **desde el primer día**, o cada cambio en Flutter
recompilará el backend.

**La IG dentro:** el IG Publisher funciona en un subdirectorio y se publica a GitHub Pages con una
Action que sube `ig/output/`. Mantener la IG junto al código que la implementa es lo que evita que
diverjan — el fallo más común en proyectos FHIR.

### 13.2. D20 — `CLAUDE.md` por componente

Un solo `CLAUDE.md` raíz que importe las convenciones de Java, Spring, TypeScript, Angular, Dart,
Flutter, Python, SQL **y** los siete subtemas de interoperabilidad sería enorme, y contradiría la regla
*"30 líneas útiles valen más que 300 que nadie lee"*.

**Solución:** `CLAUDE.md` **raíz** con la parte fija (memoria, commits, clarificación, resumabilidad),
los invariantes de proyecto y **solo** lo transversal e interoperabilidad; más un `CLAUDE.md` **por
subproyecto** que importe únicamente las convenciones de su stack.

| Fichero | Importa |
|---|---|
| `CLAUDE.md` (raíz) | `principios/*` relevantes · `interoperabilidad/fhir` · `herramientas/api-rest`, `seguridad` |
| `ig/CLAUDE.md` | `interoperabilidad/perfilado-fsh` · `terminologia` · `fhir` |
| `backend/CLAUDE.md` | `stacks/java` · `stacks/spring` · `bases-de-datos/sql` · `patrones/*` · `principios/clean-architecture` |
| `integracion/CLAUDE.md` | `stacks/java` · `stacks/spring` · `interoperabilidad/hl7-v2` · `integracion` · `fundamentos/datos-distribuidos` |
| `web-profesional/CLAUDE.md` | `stacks/typescript` · `stacks/angular` · `ux-ipo` |
| `app-ciudadano/CLAUDE.md` | `stacks/dart` · `stacks/flutter` · `ux-ipo` |
| `simuladores/CLAUDE.md` | `stacks/python` |

> Rutas de import relativas a la biblioteca como **carpeta hermana** del repo. Desde un subdirectorio,
> la ruta lleva un `../` más: `@../../BibliotecaDocumentacion/stacks/java/convenciones.md`.
> **Verificar la profundidad de cada ruta al generarlas** — es el error tonto que rompe los imports.

---

## 14. Alcance: tres rebanadas verticales

Troceado **vertical** (cada hito atraviesa de cliente a base de datos y queda demostrable), no
horizontal por capas.

### Hito 1 — el circuito básico end-to-end

Petición → espécimen → resultado → informe. **Sin Kafka, sin v2, sin Keycloak.** *Aquí ya hay un
proyecto FHIR presentable.*

**Criterios de aceptación:**

1. `sushi` compila la IG sin errores y el **IG Publisher** genera `ig/output/`; los 9 perfiles de §6.5
   existen y validan.
2. El **validador oficial** corre en CI contra `hl7.fhir.r5.core@5.0.0`; **ningún recurso de ejemplo
   sale del pipeline sin validar** contra su perfil.
3. `GET /fhir/metadata` devuelve un `CapabilityStatement` que declara `fhirVersion 5.0.0` y los
   perfiles soportados.
4. **Read-your-writes:** `POST /fhir/Patient` devuelve `201` + `Location` + `ETag` (`W/"1"`), y un `GET`
   inmediato al `Location` devuelve el recurso. Test automatizado.
5. Circuito completo por API: `Patient` → `ServiceRequest` → `Specimen` → `Observation` →
   `DiagnosticReport`, todos conformes a su perfil.
6. **Invariante de negocio probado por TDD:** un `Specimen` con `status = unsatisfactory` **no** puede
   producir un `Observation`. Test en rojo primero.
7. **Concurrencia optimista:** `PUT` con `If-Match` de versión obsoleta devuelve `412`.
8. **Búsqueda y paginación:** `GET /fhir/Observation?patient=…&code=…` con paginación seguida por
   `Bundle.link[relation=next]`, no por URL construida a mano.
9. **Errores en `OperationOutcome`** con el código HTTP correcto; nunca un `200` con error dentro.
10. **Web Angular:** alta de petición y consulta de informe, contra la API FHIR real.
11. **Generador de datos sintéticos:** crea pacientes con **apellidos dobles**, DNI/NIE con **dígito de
    control válido** y NUHSA con formato correcto. `MUÑOZ`, `ÁLVAREZ` y `PEÑA` entre los casos.
12. `docker compose up` levanta backend + PostgreSQL + web y el circuito funciona de extremo a extremo.

### Hito 2 — la interoperabilidad de verdad

Puente V2.5.1 (`ADT` + `OML` + `ORU`) con charset español · motor con mensaje original guardado, DLQ y
reproceso idempotente · Kafka con hechos clínicos y outbox transaccional · servidor de terminología con
`ConceptMap` del catálogo local · Keycloak con SMART on FHIR · app del ciudadano con SMART standalone.

### Hito 3 — lo que solo existe en R5, lo masivo y lo legal

`SubscriptionTopic` + `Subscription` por tópico · `Observation.triggeredBy` para reflejas ·
**notificador EDO** · Bulk Data `$export` + `Group` para vigilancia epidemiológica vía SMART Backend
Services · `AuditEvent`.

---

## 15. Riesgos

- **Acoplamiento de transacción con HAPI.** Escribir en el esquema HAPI dentro de la transacción del
  dominio funciona (mismo datasource), pero ata a las DAOs de HAPI. **ADR obligatorio.**
- **Doble escritura del mismo hecho.** Dominio y proyección pueden divergir por un bug de mapeo. Hace
  falta un **reconciliador** que recorra el dominio y regenere la proyección, como vía de recuperación
  oficial, no como script de emergencia.
- **La IG propia es trabajo real.** Nueve perfiles más terminología, sin US Core ni IPS de donde tirar.
- **URIs canónicas propias.** Definirlas, publicarlas y **documentar que son propias** (§4.8). Mirar
  ÚNICAS antes.
- **Sin la red de seguridad de Mirth** (D11): almacén de mensajes, reintentos y reproceso hay que
  construirlos.
- **Simular normativa real tiene un límite.** El catálogo EDO y el formato de Redalerta se modelan de
  forma **verosímil, no fiel**. Debe quedar escrito en la IG que es una simulación.
- **CI de monorepo multi-toolchain.** Filtrado por ruta desde el primer día.

---

## 16. Reglas de trabajo (no negociables)

### 16.1. Git e identidad

- **Autor de todo commit:** `Andrés Ojeda Rodríguez <andresojedarodriguez@gmail.com>`.
- **Ningún trailer ajeno.** Nada de `Co-Authored-By`, ni de Claude ni de terceros, ni trailers de
  sesión. En un commit aparecen **solo** las credenciales del usuario.
- **HispaLIS se firma.** A diferencia de `BibliotecaDocumentacion` (que no requiere firma), **este repo
  va firmado**. Antes del primer commit, comprobar `git config commit.gpgsign`; si no es `true`, **no
  commitear** y avisar al usuario de que falta el secreto `SIGNING_KEY_B64` (ver
  `plantillas/README.md` de la biblioteca). Ningún commit *unverified*.
- **Conventional Commits** (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`), atómicos.
- **Rama por unidad de trabajo**; `main` estable. **No abrir PR** salvo petición explícita.
- **Nunca** commitear secretos, `.env`, claves, `node_modules`, `target/`, `build/`, `.venv`,
  `ig/output/`, ni datos de pacientes.

### 16.2. Datos

- **Nunca datos reales de pacientes.** Solo sintéticos, en cualquier entorno.
- **Nunca PHI en URLs, logs, trazas ni analítica.**

### 16.3. Método

- **TDD obligatorio** (rojo → verde → refactor). La cobertura del comportamiento de negocio no es
  opcional.
- **El estado vive en `docs/PLAN.md`, no en el chat.** Al arrancar o retomar tras `/compact`: leer
  `PLAN.md` y el último commit **antes de nada**.
- **No dejar marcadores `TODO` sueltos en el código:** el trabajo pendiente vive en `PLAN.md`.
- **Puerta de clarificación:** ante una decisión **esencial** sin especificar que admita varias
  opciones viables, **preguntar antes de tocar nada**, todas juntas. Lo trivial o reversible se decide,
  se anota en `PLAN.md` y se sigue.

---

## 17. Lo no verificado contra fuente primaria, y material a descargar

**Nada de esto bloquea el hito 1.** Todas las decisiones están tomadas y verificadas con material
disponible.

| Pendiente | Estado | Impacto |
|---|---|---|
| **Estructura interna del CIP-SNS** | Anexo I del RD 183/2004, no contrastado | **Ninguno.** D16 decide no fijar `pattern` |
| **Especificación MLLP** | Ver §7.1. No archivada | **Ninguno en código** (HAPI lo implementa). Falta fuente citable |
| **Tabla 0354 de V2.5.1** | Difiere de la de V2.5 | Cruzarla antes de generar código. **Ambas versiones están archivadas**: se hace en local |
| **IG española ÚNICAS** | No consultada | **Único riesgo de retrabajo.** Mirar **antes** de fijar los `system` (§4.8) |

### 17.1. Material a descargar y archivar en `_fuente/` de la biblioteca

| Prioridad | Qué | Dónde | Registro |
|---|---|---|---|
| 1 | **MLLP Release 1** — *Transport Specification: MLLP, Release 1* (ed. Rene Spronk) | `https://www.hl7.org/documentcenter/public/wg/inm/mllp_transport_specification.PDF` | **No** (ruta `/public/`) |
| 2 | **MLLP Release 2** — *HL7 Version 3 Standard: Transport Specification — MLLP, Release 2* (**retirado**, §7.1) | Ficha: `https://www.hl7.org/implement/standards/product_brief.cfm?product_id=55` | Sí, cuenta gratuita |
| 3 | **Mensajes `.hl7` reales anonimizados** (`ADT`, `OML^O21`, `ORU^R01`) | Repos de motores abiertos (Mirth/OIE) en GitHub | No |
| 4 | **IG ÚNICAS** (paquete de definiciones, no el sitio renderizado) | `https://unicas-fhir.sanidad.gob.es/` | No |
| 5 | **`hl7.fhir.uv.extensions@5.3.0`** | `packages.fhir.org` o construido desde `github.com/HL7/fhir-extensions` | No |
| 6 | **SNOMED CT Starter Guide + subconjuntos** | Área de Descarga del Ministerio de Sanidad | Sí, gratuita en España |

> **Para MLLP, R1 es el documento útil:** describe el *framing* desnudo (`0x0B` … `0x1C 0x0D`), que es
> lo que implementan los motores reales y HAPI HL7v2. **R2 añadió *commit acknowledgements* para dar
> fiabilidad a V3**, capa que las integraciones V2 no usan.

**⚠️ Reglas de licencia al archivar:**

| Material | Licencia | Efecto sobre el repo de la biblioteca |
|---|---|---|
| MLLP R1 y R2 | HL7 — copia interna, **sin redistribución** | **Mantiene la regla de repo privado** (ya aplica por V2.5/V2.5.1) |
| SNOMED CT ed. española | Afiliado; gratuita en España, **sin redistribución** | **Añade una segunda licencia** con condición sobre el repo. Transcribir su aviso en el `LEEME.md` |
| `hl7.fhir.uv.extensions` | FHIR se publica bajo **CC0** | Sin restricción |
| IG ÚNICAS | Publicación oficial española | Sin restricción relevante |

**No archivar la release completa de SNOMED:** el `.git` de la biblioteca ya ronda 1,07 GB tras LOINC, y
la convención propia dice *"archiva el paquete canónico y las páginas normativas que se consultan, no
el sitio renderizado"*. Starter Guide y subconjuntos, nada más.

### 17.2. Aportaciones pendientes a la biblioteca

Hallazgos de este análisis que **deben integrarse** en `interoperabilidad/hl7-v2/`:

1. **MLLP: las capas 2 y 3 de la trampa documental** (§7.1) — que el documento normativo es un estándar
   de **V3**, y que está **retirado desde mayo de 2025 sin sustituto**.
2. Al archivar MLLP, anotar en el `LEEME.md` que son **fuentes históricas retiradas**, no vigentes.

---

## 18. Qué hay que producir a partir de este documento

Artefactos de encargo para el agente local (Claude Code en VSCode), según
`plantillas/orquestacion-web.md` de la biblioteca:

| Artefacto | Contenido |
|---|---|
| `CLAUDE.md` (raíz) + 6 por componente | Parte fija de la plantilla + parte variable de §12/§13.2. Imports recortados |
| `AGENTS.md` | Contrato de trabajo del agente |
| `docs/PLAN.md` | Objetivo · decisiones D1-D20 · checklist del hito 1 con los 12 criterios de §14 · *Estado actual* |
| `docs/diseno.md` | Este documento, tal cual |
| `docs/adr/` | ADR-0001…0006 (§18.1) |
| `.claude/settings.json` | Hook de identidad + firma |
| `.gitignore` | Java, Node, Dart, Python, `ig/output/`, secretos |
| `README.md` | Qué es, cómo levantarlo, tabla de comandos |
| `.github/workflows/` | CI con **filtrado por `paths:`** + validación FHIR + publicación de la IG |
| Esqueleto de directorios | §13.1, con `.gitkeep` donde haga falta |
| **Prompt inicial** | Para el agente local, con anatomía completa (rol · contexto · tarea · restricciones · convenciones · formato) |

### 18.1. ADR iniciales

| # | Decisión |
|---|---|
| ADR-0001 | Elección de FHIR **R5** frente a R4 (D1) |
| ADR-0002 | **Dominio propio + proyección HAPI** y la transacción compartida (D3) |
| ADR-0003 | **Identificadores españoles** y la regla de no fijar `pattern` (D16, D19) |
| ADR-0004 | **Monorepo** con la IG dentro (D10, D20) |
| ADR-0005 | **Motor propio** con HAPI HL7v2 frente a Mirth (D11) |
| ADR-0006 | **Servidor de terminología ligero e intercambiable** (D14) |

### 18.2. Modo git del encargo

**`commit`** — el agente local hace commits locales **firmados**; **push solo cuando el usuario lo
pida**. No abrir PR salvo petición explícita.

---

## 19. Fuentes

**Primarias offline (autoritativas), archivadas en `BibliotecaDocumentacion/_fuente/`:**
`hl7.fhir.r5.core@5.0.0` (todo lo marcado ✅ sobre elementos y ValueSets) · `hl7.fhir.r4b.core`
(contraste R4/R5) · HL7 **V2.5 (2003)** y **V2.5.1 (2007)** completas, con el diff medido en
`_fuente/INDICE.md` · **LOINC 2.82** y **THO 7.3.0**.

**Primaria por GitHub:** [`HL7/fhir-extensions`](https://github.com/HL7/fhir-extensions) — paquete
`hl7.fhir.uv.extensions` v5.3.0, `fhirVersion 5.0.0`. De ahí salen las URL canónicas, contextos, tipos
y madurez (FMM) de §6.2 y §6.3.

**Secundarias** (`🔍` — las normativas primarias españolas no fueron accesibles):

- Tarjeta sanitaria y CIP-SNS — [Ministerio de Sanidad](https://www.sanidad.gob.es/areas/saludDigital/tarjetaSanitariaSNS/home.htm) · [RD 183/2004 (BOE)](https://www.boe.es/buscar/act.php?id=BOE-A-2004-2591) · [RD 702/2013](https://www.boe.es/buscar/doc.php?id=BOE-A-2013-10326) · [RD 922/2024](https://www.boe.es/buscar/doc.php?id=BOE-A-2024-18620) · [Modelos de tarjetas en las CCAA](https://www.cacof.es/wp-content/uploads/2018/07/Modelo-de-Tarjetas-Sanitarias-en-las-CCAA.pdf)
- NUHSA — [Servicio Andaluz de Salud](https://www.sspa.juntadeandalucia.es/servicioandaluzdesalud/ciudadania/clicsalud/acceso-e-identificacion-en-clicsalud) · [NUHSA para mutualistas y privados](https://www.sspa.juntadeandalucia.es/servicioandaluzdesalud/archivo-comunicado/como-conocer-el-nuhsa-para-mutualistas-y-privados-espanoles-y-extranjeros)
- SNOMED CT España — [Centro Nacional de Referencia](https://www.sanidad.gob.es/areas/saludDigital/interoperabilidadSemantica/factoriaRecursos/snomedCT/referenciaSNOMED/home.htm) · [Área de descarga](https://www.sanidad.gob.es/profesionales/hcdsns/areaRecursosSem/snomed-ct/areaDescarga.htm)
- SVEA y EDO — [Vigilancia de enfermedades transmisibles](https://www.juntadeandalucia.es/organismos/sanidadpresidenciayemergencias/areas/sanidad/salud-vida/vigilancia/paginas/vigilancia-transmisibles.html) · [EDO en la cartera de servicios del SAS](https://www.sspa.juntadeandalucia.es/servicioandaluzdesalud/profesionales/cartera-de-servicios/atencion-primaria/i-area-de-atencion-la-persona/2-atencion-especifica/24-atencion-problemas-infecciosos-de-especial-relevancia/245-enfermedades-de-declaracion-obligatoria)
- Diraya y el MPA — [Diraya (SAS)](https://www.sspa.juntadeandalucia.es/servicioandaluzdesalud/profesionales/sistemas-de-informacion/diraya) · [100 millones de pruebas analíticas](https://www.sspa.juntadeandalucia.es/servicioandaluzdesalud/ayudadigital/novedades/noticia/la-historia-de-salud-digital-del-sas-supera-los-cien-millones-de-pruebas)
- NICA y autorización sanitaria — [Autorización y registro de centros sanitarios](https://www.juntadeandalucia.es/temas/sectores/sanitario/autorizacion-acreditacion.html) · [Registro andaluz: laboratorio, óptica y ortopedia](https://icofma.es/es/114-vocalias/vocalia-de-analisis-e-industria/contenido-de-analisis-e-industria/contenido-de-analisis/legislacion-analisis/2566-registro-andaluz-de-centros-servicios-y-establecimientos-sanitarios-laboratorio-optica-y-ortopedia)
- Conservación de la historia clínica — [Plazos por comunidad autónoma](https://blog.psnsercon.com/los-plazos-de-conservacion-de-las-historias-clinicas-en-las-distintas-comunidades-autonomas/) · [Requerimientos y plazos](https://www.unitecoprofesional.es/blog/requerimientos-historia-clinica-plazos-conservacion/)
- ISO 15189:2022 — [ENAC, nueva revisión](https://www.enac.es/actualidad/nueva-revision-iso-15189) · [ENAC, laboratorios clínicos](https://www.enac.es/que-hacemos/servicios-de-acreditacion/laboratorios-clinicos)
- MLLP — [Ficha del producto (HL7)](https://www.hl7.org/implement/standards/product_brief.cfm?product_id=55) · [MLLP Release 1 (PDF público)](https://www.hl7.org/documentcenter/public/wg/inm/mllp_transport_specification.PDF) · [Aviso de retirada de MLLP R2](https://standups.hl7.org/2025/06/25/retired-normative-publication-of-hl7-version-3-standard-transport-specification-mllp-release-2/)
- IG española de referencia — [ÚNICAS Rare Diseases HL7 FHIR IG](https://unicas-fhir.sanidad.gob.es/componentesFHIRDetallados.html)
