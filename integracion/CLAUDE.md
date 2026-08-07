# CLAUDE.md — `integracion/` (motor de integración HL7 v2)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado. **Componente del hito 2**: no se
> toca hasta cerrar el hito 1.

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/stacks/java/convenciones.md
@../../BibliotecaDocumentacion/stacks/spring/convenciones.md
@../../BibliotecaDocumentacion/interoperabilidad/hl7-v2/convenciones.md
@../../BibliotecaDocumentacion/interoperabilidad/integracion/convenciones.md
@../../BibliotecaDocumentacion/fundamentos/datos-distribuidos/convenciones.md

---

## Qué es y qué no es

Servicio **Spring Boot propio** con la librería **HAPI HL7v2** (`ca.uhn.hl7v2`), que aporta listener
MLLP, parser y generación de acuses (D11). **No es Mirth**: los canales son código, se despliegan por
el mismo circuito que el resto y se revisan como código. Nunca se edita un canal en una consola.

**Versión fijada: HL7 V2.5.1 (2007)** (D12). ⚠️ **La tabla 0354 tiene contenido distinto entre V2.5 y
V2.5.1** — crúzala contra las dos versiones archivadas en `_fuente/` de la biblioteca antes de generar
código; no asumas equivalencia.

## Los dos planos no se mezclan (D4)

| | Plano de **aplicaciones** | Plano de **sistemas** |
|---|---|---|
| Formato | **FHIR R5, solo** | **HL7 V2.5.1, solo** |
| Transporte | HTTPS | **MLLP sobre TLS** |
| Entra por | API FHIR | **este motor** |

**HL7 v2 no entra por el front** y **no llega a la API FHIR sin traducir**. El motor **es** el punto
de conversión explícito y auditable. Si se funden los dos contratos en una puerta, el mapeo deja de
poder auditarse — que es exactamente el fallo que este motor existe para evitar.

## Reglas del canal

- **D5 — el motor escribe contra la propia API FHIR**, autenticándose como cliente `system/` vía SMART
  Backend Services. **Un solo camino de escritura**, con las mismas validaciones, invariantes y
  auditoría que cualquier otro cliente. **Nunca** invoques comandos de dominio directamente.
- **Guarda el mensaje original íntegro** antes de tocarlo, con metadatos indexables (paciente,
  episodio, `MSH-10`).
- **Deduplica por `MSH-10` en el motor, antes de escribir.**
- **DLQ y punto de reproceso idempotente.** Es lo que se pierde al no usar Mirth y hay que construir.
- **Charset en `MSH-18`** y normalización en la entrada del canal. **`MUÑOZ`, `ÁLVAREZ` y `PEÑA` son
  casos de prueba obligatorios**: las tildes y la `ñ` rompen tuberías v2 constantemente.
- **Agrupa con bundles `transaction`** solo si aparece un cuello de botella medido, no por defecto.
- Estructura del canal: `origen → filtro → transformador → destino`.

## El catálogo se pregunta, no se lee (D14, D15)

`OBR-4`, `OBX-3` y `SPM-4` pasan por `CatalogoDelLaboratorio`, que resuelve contra el **servidor de
terminología** (`hispalis.terminologia.servidor`) con las cuatro operaciones estándar: `$lookup` para
el nombre y la unidad UCUM —que el `CodeSystem` declara como propiedad `unidad-ucum`—,
`$validate-code` para saber si una prueba o un tipo de muestra existen, `$translate` para el LOINC y
su vuelta, y `$expand` para contar el catálogo al arrancar. **Ninguna tabla dentro del motor.**

- **La vuelta del mapa solo se invierte donde hay equivalencia.** Con
  `source-is-broader-than-target`, varios LOINC caerían en el mismo código local y elegir uno
  inventaría una precisión que el mapa dice que no tiene.
- **⚠️ La vuelta del `$translate` en R5 se pide con `targetCode`, y HAPI 8.10 no lo implementa**
  (`HAPI-1154`). El cliente pide **las dos formas, la de R5 primero**, y cae a `reverse=true` de R4.
- **Lo que no se traduce va a la bandeja de errores**, que es reprocesable. Aceptar un código sin
  comprobarlo metería en el laboratorio una prueba que quizá no oferta, y eso no se deshace.
- **Los tests corren contra `arnes/TerminologiaDePrueba`**, un servidor HTTP cargado con lo que
  produce SUSHI. Ahí es donde vive la lectura de ficheros, y ahí es donde debe vivir. Que las cuatro
  operaciones se comporten igual contra el HAPI real lo comprueba `ContraElServidorRealTest`, apagado
  salvo que se le diga dónde mirar con `HISPALIS_TERMINOLOGIA_REAL`.

## Contratos

| Entrante (MLLP/TLS) | Produce |
|---|---|
| `ADT^A01` / `A08` | `Patient` (demografía, altas y correcciones) |
| `OML^O21` | `ServiceRequest` + `Specimen` |
| `ORU^R01` | `Observation` (resultado bruto del analizador) |

**Saliente:** `ORU^R01` hacia el HIS cuando el informe se valida.

## MLLP — la trampa documental (§7.1 del diseño)

El apéndice B de V2.5/V2.5.1 está **vacío**; el documento normativo es un estándar de **HL7 V3**
(*Transport Specification — MLLP, Release 2*) y está **retirado desde mayo de 2025 sin sustituto
designado**. **Impacto en el código: ninguno** — HAPI HL7v2 implementa el *framing*
(`0x0B` … `0x1C 0x0D`) y nunca se escribe a mano. Lo que falta es fuente citable, no código.

## Comandos

```bash
cd integracion
./mvnw verify
./mvnw spring-boot:run
```
