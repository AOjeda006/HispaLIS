---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-06
tags: [adr, hl7-v2, v2.5.1, tabla-0354, message-structure, adt, mapeo, integracion]
---

# ADR-0018: La tabla 0354 se contradice consigo misma, y hay que elegir fuente antes de mapear

- **Estado:** aceptado
- **Fecha:** 2026-08-06

## Contexto

`MSH-9` de HL7 v2 tiene tres componentes: tipo de mensaje (`ADT`), evento (`A01`) y **código de
estructura** (`ADT_A01`). El tercero es el que dice qué segmentos vienen y en qué orden, y es el que
usa un *parser* para elegir la clase con la que decodificar. La tabla que lo define es la **0354
(*message structure*)**, y en V2.5.1 esa tabla está publicada **dos veces**: en el capítulo 2 (control)
y en el apéndice A (definiciones de tablas).

Las dos copias **no dicen lo mismo**. No es un rumor de foro: se ha medido en local sobre los PDF
originales de las dos publicaciones, archivados en `_fuente/_externos/hl7-v2/` de la biblioteca.

### El cruce medido

Extracción con `pdftotext -layout` de `CH02.pdf` / `AppendixA.pdf` (V2.5, 2003) y `V251_CH02.pdf` /
`V251_Appendix_A.pdf` (V2.5.1, 2007), y cruce de los códigos de estructura resultantes:

| Fuente | Códigos de estructura |
|---|---|
| V2.5 — capítulo 2 | **194** |
| V2.5 — apéndice A | **194** (los mismos 194) |
| V2.5.1 — capítulo 2 | **199** |
| V2.5.1 — apéndice A | **194** (sin tocar respecto a V2.5) |

**En V2.5 las dos fuentes coinciden exactamente. La divergencia la introduce V2.5.1**, que reescribió
la tabla del capítulo y dejó la del apéndice como estaba. Cuadra la aritmética: 194 − 5 + 10 = 199.

**Diez códigos existen solo en el capítulo 2 de V2.5.1** (y en ninguna de las dos fuentes de V2.5):

```
ADT_A12  BRP_O30  MFR_M04  MFR_M05  MFR_M06  MFR_M07  RPI_I04  RSP_K25  RSP_K31  RSP_Q11
```

**Cinco códigos existen solo en el apéndice A de V2.5.1** (y en las dos fuentes de V2.5, que es de
donde vienen: el apéndice simplemente no se actualizó):

```
BRP_030  ORU_R31  ORU_R32  RDE_O01  RRA_O02
```

Los eventos afectados, leídos del capítulo 2 de cada versión:

| Estructura | V2.5, capítulo 2 | V2.5.1, capítulo 2 |
|---|---|---|
| `ADT_A09` | `A09, A10, A11, A12` | `A09, A10, A11` — `A12` se va a `ADT_A12` |
| `MFR_M01` | `M01`…`M06` | `M01, M02, M03` — el resto, a `MFR_M04`…`M07` |
| `ORU_R30` | `R30` | `R30, R31, R32` — absorbe las otras dos |
| `RPI_I01` | `I01, I04` | `I01` — `I04` se va a `RPI_I04` |

Dos rarezas más, verificadas en el texto:

- **`BRP_030` con cero** en el apéndice contra **`BRP_O30` con letra O** en el capítulo. V2.5 llevaba
  el cero en los dos sitios. Es un renombrado aplicado a medias, y las dos grafías son
  indistinguibles a ojo en la mayoría de tipografías.
- La fila de `MFR_M05` del capítulo 2 declara que cubre el evento **`Mo5`**, con o minúscula.

> **Refinamiento sobre la biblioteca.** `interoperabilidad/hl7-v2/referencia.md` da **nueve** códigos
> solo-capítulo; la medición de aquí da **diez**, y el que falta es `MFR_M05`. Se entiende por qué:
> su fila es precisamente la del `Mo5` mal escrito, y cualquier extracción que valide la columna de
> evento la descarta. La biblioteca **no se edita a mitad de proyecto** —la aportación se acumula y
> se entrega junta, en `docs/destilacion.md`—; queda anotado aquí para corregirlo cuando toque.

### Por qué importa más de lo que parece

El apéndice A es un anexo de tablas: es exactamente el fichero del que alguien saca un CSV para
alimentar un generador. Y el capítulo 2 es el normativo que lee quien implementa a mano. **Dos
implementaciones igual de "conformes" a V2.5.1 enrutan `A12` a estructuras distintas** — una a
`ADT_A09` y otra a `ADT_A12` — y ninguna de las dos está equivocada según su fuente.

## Decisión

**Fijar el capítulo 2 de V2.5.1 como fuente única de la tabla 0354 en este proyecto**, y hacer que el
canal **rechace** un `MSH-9-3` que no cuadre con lo que espera, en vez de intentar reconciliarlo.

- **Manda el capítulo 2.** Es la parte normativa de control, es la que V2.5.1 sí actualizó, y es lo
  que implementa HAPI HL7v2 en `hapi-structures-v251`. Elegir el apéndice sería elegir la copia que
  el editor olvidó tocar.
- **Y por encima del capítulo 2, manda la guía de interfaz** el día que haya una. Es la regla general
  de v2 y este caso es su justificación concreta: cuando el propio estándar se contradice, el acuerdo
  bilateral es lo único que queda.
- **Lo que el proyecto usa, con su código en V2.5.1:**

  | Evento | Estructura (`MSH-9-3`) | Estado |
  |---|---|---|
  | `A01` admisión | `ADT_A01` | en las dos fuentes, sin cambios desde V2.5 |
  | `A08` corrección de filiación | **`ADT_A01`** | en las dos fuentes, sin cambios desde V2.5 |
  | `O21` petición de laboratorio | `OML_O21` | en las dos fuentes, sin cambios desde V2.5 |
  | `R01` resultado no solicitado | `ORU_R01` | en las dos fuentes, sin cambios desde V2.5 |

  **Ninguna de las cuatro cae en la zona de divergencia.** Es la buena noticia del cruce: el canal de
  este hito no depende de qué fuente se elija. Pero eso solo se sabe **después** de cruzarlas.

- **`ADT_A08` no existe.** No está en el capítulo 2 ni en el apéndice A, ni en V2.5 ni en V2.5.1: los
  eventos `A01`, `A04`, `A08` y `A13` comparten la estructura `ADT_A01`, y así lo escribe el propio
  ejemplo de `MSH` del capítulo 2, que en las dos versiones lleva `ADT^A08^ADT_A01`. Es un error tan
  natural de cometer que **estaba escrito en nuestro propio plan de trabajo** antes de este cruce.
- **El canal valida el tercer componente cuando viene y no lo exige cuando no.** `MSH-9-3` es opcional
  en la práctica; muchos HIS lo dejan vacío. Si viene y no es `ADT_A01`, el mensaje se rechaza con un
  `AR` que **nombra el código correcto** en el `ERR`, en lugar de aceptarlo y decodificar con la clase
  equivocada.

## Consecuencias

- **El mapeo se escribió sabiendo lo que se mapeaba**, no dando por buena la primera tabla
  encontrada. `CanalAdtPaciente` fija `ESTRUCTURA = "ADT_A01"` para `A01` y para `A08`, con este ADR
  citado en el sitio donde se decide.
- **Un `AR` explícito en vez de un fallo silencioso.** Un emisor que mande `ADT^A08^ADT_A08` recibe un
  acuse que le dice el código bueno. La alternativa —dejarlo pasar y que HAPI decodifique con lo que
  encuentre— produce un `Patient` con campos en blanco y nadie sabe por qué.
- **El simulador del HIS emite lo mismo.** `simuladores/his/mensajes.py` tiene la constante
  `ESTRUCTURA_ADT = "ADT_A01"` con la misma referencia; si el arnés emitiera `ADT_A08` estaría
  probando el camino de rechazo creyendo probar el normal.
- **Queda un cabo suelto conocido:** si algún día se acepta `OML_O21` de un emisor que lea el
  apéndice, hay que volver a mirar este ADR — el apéndice tiene la columna de eventos **desplazada
  una fila** en la extracción, y ahí sí conviene leer el PDF a ojo antes de fiarse de nada.
- **La lección es portable y por eso es un ADR y no una nota:** *antes de generar mapeo a partir de
  una tabla de un estándar, cruzar la tabla consigo misma si aparece publicada más de una vez.* Vale
  igual para las tablas repetidas de v2, para los `ValueSet` que una IG define y redefine, y para
  cualquier catálogo que venga en un anexo además de en el cuerpo.

## Alternativas consideradas

- **Aceptar los dos códigos** (`ADT_A01` y `ADT_A08`) y normalizar al bueno. Es tolerante y esconde el
  problema: el emisor nunca se entera de que manda algo que no existe, y el día que mande de verdad
  una estructura distinta, el canal ya se ha acostumbrado a no mirar.
- **Ignorar `MSH-9-3` por completo** y decidir la estructura por el evento. Funciona hasta el primer
  emisor que use una estructura local o un `Z`-evento, y renuncia gratis a una comprobación que el
  estándar puso ahí para eso.
- **Tomar el apéndice A como fuente** porque es más fácil de extraer. Es justo la trampa: se extrae
  mejor porque es una tabla plana, y es la copia desactualizada.
- **No cruzar nada y fiarse de HAPI.** HAPI acierta aquí, pero eso solo se sabe habiéndolo cruzado, y
  el mapeo del canal no es HAPI: es código nuestro que decide qué evento va a qué estructura.

## Cómo reproducirlo

Los PDF están en `_fuente/_externos/hl7-v2/{v2.5-2003,v2.5.1-2007}/` de la biblioteca —material con
copyright de HL7, en repositorio privado y **sin copiar aquí ni tablas ni párrafos literales**. El
cruce es local:

```bash
pdftotext -layout v2.5-2003/CH02.pdf            v25-ch02.txt
pdftotext -layout v2.5-2003/AppendixA.pdf       v25-apA.txt
pdftotext -layout v2.5.1-2007/V251_CH02.pdf     v251-ch02.txt
pdftotext -layout v2.5.1-2007/V251_Appendix_A.pdf v251-apA.txt
```

De los cuatro ficheros se extraen los tokens `^[A-Z]{3}_[A-Z0-9]{2,3}$` del bloque de la tabla 0354
—más `ACK`, que es el único código de estructura sin subrayado— y se cruzan los conjuntos. **Dos
avisos para quien lo repita**, que costaron tres intentos:

- El bloque del apéndice se corta en los saltos de página e intercala la cabecera de la tabla
  siguiente; contar filas seguidas da de menos. La comprobación fiable es **buscar cada código del
  capítulo dentro del texto completo del apéndice**, no parear filas.
- En el apéndice de V2.5.1 la **columna de descripción va desplazada una fila** respecto a la de
  código. Sirve para saber **qué códigos hay**; no sirve para saber **qué eventos cubre cada uno**.
