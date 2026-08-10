### HispaLIS

Guía de implementación de **HispaLIS**, un **Sistema de Información de Laboratorio** (SIL) para un
laboratorio clínico privado de Sevilla, sobre **HL7 FHIR R5** (`5.0.0`).

Perfila el circuito completo del laboratorio —petición, espécimen, resultado e informe— en el
contexto sanitario español: identificadores de paciente reales, apellidos dobles, códigos INE y
terminología LOINC, SNOMED CT Edición Española y UCUM.

---

### Avisos

#### ⚠️ Esto es una simulación

HispaLIS **no es un laboratorio real ni un producto sanitario**. Es un proyecto de aprendizaje y
demostración técnica. Todos los datos que aparecen en esta guía y en el sistema son **sintéticos y
generados por ordenador**: no existe ni ha existido nunca ningún dato de un paciente real, en ningún
entorno. Los identificadores de los ejemplos son inventados y no corresponden a ninguna persona.

#### ⚠️ Las URIs canónicas son propias, no oficiales

España **no tiene un juego oficial consolidado** de URIs canónicas para los espacios de nombres de
identificador sanitario. Esta guía resuelve así:

- **Adoptados del Ministerio de Sanidad.** Los OID que la guía española de
  [ÚNICAS](https://unicas-fhir.sanidad.gob.es/) usa de forma consistente para el **DNI/NIE**
  (`urn:oid:1.3.6.1.4.1.19126.3`) y el **CIP-SNS** (`urn:oid:2.16.724.4.40`) se adoptan tal cual, en
  vez de inventar unos propios. ÚNICAS no los publica como `NamingSystem` canónico, pero son los del
  registro español y los usa la autoridad nacional.
- **Propios de este proyecto.** El resto —NHC, NUHSA, NASS, NICA, NIF y número de colegiado— cuelga
  de `https://aojeda006.github.io/HispaLIS/sid/…`. **Son URIs propias de esta simulación, no
  oficiales, y no deben usarse fuera de ella.** Se definen porque no existe una alternativa oficial
  publicada; en particular, no hay OID publicado para el CIP autonómico de Andalucía (el NUHSA).

#### ⚠️ ISO 15189 está fuera de alcance

**UNE-EN ISO 15189:2022** es una **acreditación voluntaria**, no una obligación legal — la obligación
real de un laboratorio clínico en Andalucía es la **autorización sanitaria** del Decreto 69/2008 y su
inscripción en el registro NICA. Esta guía **no pretende cumplir ISO 15189** ni sirve como evidencia
para acreditarse. La norma se cita únicamente como justificación de diseño de la trazabilidad.

---

### Perfiles

| Perfil | Recurso | Para qué |
|---|---|---|
| [PacienteLabES](StructureDefinition-paciente-lab-es.html) | `Patient` | Paciente, con la jerarquía real de identificadores del SNS y apellidos dobles |
| [PeticionLab](StructureDefinition-peticion-lab.html) | `ServiceRequest` | Línea de petición analítica |
| [EspecimenLab](StructureDefinition-especimen-lab.html) | `Specimen` | Muestra recibida, con número de acceso y motivo de rechazo |
| [ResultadoLab](StructureDefinition-resultado-lab.html) | `Observation` | Resultado, con unidad UCUM, rango de referencia y reflejas |
| [InformeLab](StructureDefinition-informe-lab.html) | `DiagnosticReport` | Informe validado y su PDF |
| [LaboratorioOrg](StructureDefinition-laboratorio-org.html) | `Organization` | Centro sanitario, con NICA y NIF |
| [FacultativoLab](StructureDefinition-facultativo-lab.html) | `Practitioner` | Profesional que solicita o valida |
| [CoberturaLab](StructureDefinition-cobertura-lab.html) | `Coverage` | Quién paga: el paciente o una aseguradora |
| [NotificacionEDO](StructureDefinition-notificacion-edo.html) | `Task` | Declaración obligatoria a Salud Pública |

### Terminología

| Artefacto | Qué es |
|---|---|
| [CatalogoPruebas](CodeSystem-catalogo-pruebas.html) | El **dialecto local**: los códigos con los que el laboratorio pide y firma sus pruebas, cada uno con su unidad UCUM |
| [CatalogoALoinc](ConceptMap-catalogo-a-loinc.html) | El **traductor** del dialecto a LOINC |
| [PruebasDelCatalogo](ValueSet-pruebas-del-catalogo.html) | Todas las pruebas ofertadas; es a lo que se atan `PeticionLab.code` y `ResultadoLab.code` |
| [TiposMuestra](ValueSet-tipos-muestra.html) | Tipos de espécimen aceptados, en SNOMED CT |
| [MotivosRechazoMuestra](ValueSet-motivos-rechazo-muestra.html) | Por qué se rechaza una muestra |
| [ResultadosCualitativos](CodeSystem-resultados-cualitativos.html) | Positivo, negativo e **indeterminado**: los valores de una prueba que no da cifra |
| [CatalogoEdo](ValueSet-catalogo-edo.html) | Pruebas cuyo resultado positivo obliga a declarar a Salud Pública |
| [EnfermedadesEdo](CodeSystem-enfermedades-edo.html) | Las enfermedades que se declaran. **Relación simulada, no la oficial** |

#### Dónde vive la regla de una declaración obligatoria

En el propio concepto de [CatalogoPruebas](CodeSystem-catalogo-pruebas.html), en dos propiedades que
van juntas: `enfermedad-edo` dice **qué** se declara y `resultado-que-declara`, **con qué valor**.
Un `LEGIOAG` con `POS` obliga a declarar una legionelosis; con `NEG` o con `IND`, no.

Está ahí y no en un `ConceptMap` a propósito. R5 tiene un elemento hecho para condicionar un mapeo
—`ConceptMap.additionalAttribute` con `element.target.dependsOn`— y sería el sitio natural, pero el
servidor de terminología de referencia de esta guía **no lo sirve** (HAPI 8.10 no acepta el parámetro
`dependency` de `$translate` ni devuelve `dependsOn`). Publicarlo ahí dejaría la mitad de la regla en
un elemento que nadie puede leer, así que la regla entera vive donde un solo `$lookup` la trae
completa — el mismo sitio que el umbral crítico y la prueba refleja.

> **La obligación es real; el catálogo y el destinatario, simulados.** Todos los centros sanitarios
> de Andalucía, **públicos y privados**, forman parte del Sistema de Vigilancia Epidemiológica
> (Decreto 66/1996), y la relación de EDO la fija la Orden de 19 de diciembre de 1996, actualizada
> por la de 12 de noviembre de 2015. La relación real es mucho más amplia que la de aquí, y el
> contrato de Redalerta no es público.

Todos los *bindings* son **extensibles**, nunca `required`: ninguno de estos conjuntos está cerrado
en la práctica, y declarar cerrado lo que no lo está solo produce rechazos falsos.

Dos avisos sobre los términos que se muestran:

- **Los `display` de LOINC van en inglés y sin alterar.** Su licencia no permite cambiar el contenido
  de sus campos, y la variante lingüística española de LOINC 2.82 es **parcial**: traduce los ejes,
  no el nombre largo. El español que ve el usuario es el `display` del catálogo local.
- **Los conceptos SNOMED se enumeran sin `display`.** El término lo resuelve el servidor de
  terminología; mientras se resuelva contra la edición internacional se verán en inglés, y pasarán a
  español al cargar la Edición Española del SNS.

### Qué exige `Must Support`

Una guía que reparte `Must Support` sin decir qué significa no está exigiendo nada. En HispaLIS, un
elemento marcado `MS` obliga a un sistema conforme a:

1. **Poblarlo** cuando el dato existe y se conoce. No es válido omitir un elemento `MS` disponible.
2. **Procesarlo** al recibirlo: almacenarlo y devolverlo tal cual en una lectura posterior, sin
   perderlo ni truncarlo — en particular, sin perder tildes ni la `ñ`.
3. **No romperse** cuando el elemento falta, si su cardinalidad mínima es `0`. La ausencia de un
   `MS` opcional es un caso normal, no un error.

`MS` **no** implica que el elemento sea obligatorio: la obligatoriedad la fija la cardinalidad. Un
`0..1 MS` significa «puede faltar, pero si está hay que tratarlo bien».

Cuando la ausencia de un dato *es* información —un paciente extranjero que no tiene DNI, frente a
uno cuyo DNI no se registró—, se declara con la extensión estándar
[`data-absent-reason`](http://hl7.org/fhir/StructureDefinition/data-absent-reason). Nunca con un
valor vacío.

---

### Alcance

El circuito modelado es: **petición → extracción → espécimen → resultado → validación facultativa →
informe → entrega**. Deliberadamente **no** cubre una historia clínica electrónica: HispaLIS es un
SIL, no una HCE.

Queda **fuera**: la conexión al Módulo de Pruebas Analíticas de Diraya (su contrato de interfaz no es
público), la HCDSNS y el Nodo SNS, Receta XXI, el CMBD y el Esquema Nacional de Seguridad.
