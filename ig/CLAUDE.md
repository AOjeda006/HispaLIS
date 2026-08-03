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
| `Observation.bodyStructure` | no existe | `0..1 Reference` | |
| `DiagnosticReport.composition` | no existe | `0..1 Reference` | |
| `Specimen.combined` / `.role` / `.feature` | no existen | nuevos | |
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

## Artefactos a producir (§6.5 del diseño)

9 perfiles — `PacienteLabES`, `PeticionLab`, `EspecimenLab`, `ResultadoLab`, `InformeLab`,
`LaboratorioOrg`, `FacultativoLab`, `CoberturaLab`, `NotificacionEDO` — más la extensión propia
`codigo-ine`, el `CodeSystem` del catálogo local, el `ConceptMap` catálogo → LOINC y los `ValueSet`
de tipos de muestra, motivos de rechazo y catálogo EDO.

## Comandos

```bash
cd ig
sushi .                                   # compila el FSH → fsh-generated/
java -jar publisher.jar -ig .             # IG Publisher → output/
```

Ejemplos y perfiles se validan en CI (`.github/workflows/ci-ig.yml`) contra
`hl7.fhir.r5.core@5.0.0`. La IG se publica a GitHub Pages desde `ig/output/`.
