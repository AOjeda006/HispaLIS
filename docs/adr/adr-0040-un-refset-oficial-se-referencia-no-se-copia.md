---
tipo: referencia
stack: []
aplica_a: [ig, terminologia]
revisado: 2026-08-15
tags: [adr, terminologia, snomed, refset, espana, valueset, fhir]
---

# ADR-0040: Un refset oficial se referencia, no se copia

- **Estado:** aceptado — **aplicación diferida al ítem 42**, por el mismo bloqueo de licencia
- **Fecha:** 2026-08-15

## Contexto

La extensión española del SNS publica **80 conjuntos de referencias**, y dos de ellos hablan
exactamente de lo mismo que dos artefactos de esta guía:

| Refset del SNS | `refsetId` | Miembros | Lo que hay en esta guía |
|---|---|---|---|
| Tipos de muestra de laboratorio | `900000221000122100` | **617** | `ValueSet/tipos-muestra`, con **10** códigos escritos a mano |
| Tipos de documento para identificación personal | `900000251000122107` | **3** | El alias `$SCT_ES_REFSET_DOCUMENTOS`, declarado y sin usar |
| Enfermedades de Declaración Obligatoria | `900000091000122109` | — | `CodeSystem/enfermedades-edo`, propio y deliberado |

El primero no es un solapamiento casual: el refset del SNS está construido para la variable «Tipo de
muestra» del Informe de Resultados de Pruebas de Laboratorio del CMDIC (RD 1093/2010, Anexo V), que
es la misma variable que este laboratorio informa. Hay **dos listas para el mismo campo y ninguna
relación declarada entre ellas**.

Que la lista local sea más corta es correcto y no se discute: un laboratorio oferta lo que oferta, y
el catálogo nacional es el universo, no la oferta. Lo que no es correcto es que la relación entre las
dos **no se pueda comprobar**. Si el día de mañana el SNS retira uno de esos diez códigos o lo
sustituye, aquí no se entera nadie: el `ValueSet` seguirá enumerándolo y el servidor seguirá
resolviéndolo contra la edición internacional, que no tiene por qué haberlo retirado a la vez.

Al mirar cómo se referencia un refset aparecieron dos trampas más, las dos ya corregidas y las dos
del mismo tipo — configuración escrita, plausible y que nadie usa todavía:

- **`?fhir_vs-refset/…`, con un guion.** Un `ValueSet` implícito de SNOMED tiene cinco formas y una
  sintaxis, `?fhir_vs=refset/<refsetId>`. Con un guion queda una URI que SUSHI compila y que ningún
  servidor resuelve.
- **`$SCT_ES` delante de una almohadilla.** `http://snomed.info/sct/900000001000122104` es la URI de
  la **edición**: vale como base de un `ValueSet` implícito y no vale como `system` de un `Coding`,
  donde SNOMED es siempre `http://snomed.info/sct` y la edición viaja en `version`.

Y una tercera cosa que las fichas dicen y conviene no descubrir sobre la marcha: **un refset oficial
no es una regla de negocio**. La ficha del de EDO advierte que no forma parte de ningún protocolo de
declaración —es una lista de trastornos, no la obligación de declararlos, ni el plazo, ni el
criterio de caso—; la de tipos de muestra advierte que no es un recurso exhaustivo ni cerrado.

## Decisión

1. **Donde exista un refset oficial para la misma variable, el subconjunto local se declara contra
   él.** La lista local se queda —es la oferta del laboratorio— y pasa a ser **verificable**: cada
   código suyo tiene que estar en el refset, y si no lo está, la carga se para y lo dice. Es la misma
   regla que el cargador ya aplica con los conceptos retirados, aplicada a la pertenencia.
2. **La comprobación se hace contra el RF2, en la carga.** El fichero de miembros del refset
   (`der2_Refset_Simple*Snapshot*`, filtrando por `refsetId`) es el único sitio donde esa
   información está, y es el sitio donde el cargador ya está mirando. No se hace contra el servidor
   de terminología: lo que se le sube es un `CodeSystem` **fragmentario** con los conceptos que la
   guía cita, sin datos de pertenencia, así que no puede expandir un `?fhir_vs=refset/…` ni aunque se
   le pregunte bien.
3. **Un refset se referencia con su `ValueSet` implícito y con la sintaxis exacta**
   —`{edición}?fhir_vs=refset/{refsetId}`—, y **la URI de edición nunca es un `system`**. Las dos
   reglas están guardadas por un test que lee el FSH de la guía, porque un alias sin uso no lo
   comprueba nadie.
4. **El refset aporta contenido; la regla de negocio es del proyecto.** Qué enfermedades declara este
   laboratorio, con qué plazo y a partir de qué resultado, sigue viviendo en artefactos propios con
   su fuente citada dentro. Que exista un refset oficial de EDO no cambia eso: su propia ficha dice
   que no es un protocolo de declaración.

## Consecuencias

- **Nada de esto se puede aplicar hoy**, y por eso el estado dice *aplicación diferida*: no hay
  release en la máquina (ítem 42) y sin ella no hay fichero de miembros contra el que comprobar.
  Queda escrito para que el día que llegue sea trabajo y no investigación.
- Los diez códigos de `tipos-muestra` pasarán entonces por una comprobación que hoy no tienen. **Es
  posible que alguno no esté en el refset del SNS**: son códigos internacionales elegidos por lo que
  significan, no por su pertenencia. Que salte no será un fallo del código: será la respuesta a una
  pregunta que hasta ahora no se había hecho.
- `ci-ig` no cambia. Se ha valorado atar el `ValueSet` al refset con la forma de intersección que
  permite FHIR —`include` con `system` y `valueSet` a la vez: «los códigos tienen que estar en todos
  los conjuntos listados»— y el precio sería que el validador oficial, que resuelve contra
  `tx.fhir.org`, tendría que expandir un refset de la extensión española. No lo sirve, porque
  requiere licencia de afiliado.
- El proyecto se queda con **dos listas para la misma variable** hasta entonces, pero con la relación
  entre ellas escrita en el `ValueSet` y en este ADR, que es la diferencia entre una deuda y un
  descuido.

## Alternativas consideradas

- **Sustituir la lista local por el refset entero** (`include codes from system $SCT and valueset
  $SCT_ES_REFSET_MUESTRAS`). Es una línea, y publicaría que este laboratorio acepta 617 tipos de
  muestra. El `ValueSet` de una oferta no es el catálogo nacional.
- **Comprobar la pertenencia con `$validate-code` contra el servidor.** Sería lo natural y no
  funciona: el `CodeSystem` que se sube es un fragmento con los conceptos citados y sin datos de
  refset. Cargar los refsets completos para poder preguntar significaría subir la extensión entera,
  que es justo lo que la licencia no permite dejar aquí.
- **Copiar los 617 códigos a la guía.** Sería redistribuir contenido licenciado, y quedaría
  desactualizado en la siguiente publicación semestral.
- **Anotar los diez códigos con un comentario que diga de qué refset salen.** Es lo que hay hoy en
  otros sitios y es exactamente lo que este ADR considera insuficiente: un comentario no se ejecuta.

## Lo reutilizable

1. **Antes de escribir una lista de códigos, buscar si la autoridad ya publica esa lista.** No para
   copiarla —eso suele violar la licencia y siempre envejece— sino para **declarar la relación** con
   ella. Una lista local sin relación declarada con la oficial es una copia que nadie va a sincronizar.
2. **«Está en la lista oficial» es una afirmación comprobable, y donde no se comprueba, es una
   creencia.** El sitio donde comprobarla es el punto por el que el contenido entra al sistema.
3. **Un refset oficial dice qué es un concepto, no qué hay que hacer con él.** La lista de EDO no es
   la obligación de declarar; el catálogo de muestras no es la oferta del laboratorio. Confundir el
   vocabulario con la política es la forma habitual de acabar con reglas de negocio escondidas dentro
   de un `ValueSet`.
4. **Un refset con cero miembros no está roto: puede estar avisando.** La extensión del SNS vació dos
   en esta publicación como paso previo a inactivarlos en la siguiente. Una expansión vacía es un
   aviso con ruta de migración, y tratarla como un error del servidor es perder el aviso.
5. **La configuración que nadie usa todavía es la que más falla, y falla el peor día.** Los dos
   errores de URI de este ADR llevaban meses escritos, en verde y sin consecuencias, esperando al día
   en que alguien fuera a usarlos con la release recién descargada y la atención puesta en otra cosa.
