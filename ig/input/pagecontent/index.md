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

### Alcance

El circuito modelado es: **petición → extracción → espécimen → resultado → validación facultativa →
informe → entrega**. Deliberadamente **no** cubre una historia clínica electrónica: HispaLIS es un
SIL, no una HCE.

Queda **fuera**: la conexión al Módulo de Pruebas Analíticas de Diraya (su contrato de interfaz no es
público), la HCDSNS y el Nodo SNS, Receta XXI, el CMBD y el Esquema Nacional de Seguridad.
