---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-10
tags: [adr, fhir, r5, hapi, searchparameter, conformidad, toolchain]
---

# ADR-0029: Un `SearchParameter` en `draft` se publica, se lee y no se indexa

- **Estado:** aceptado
- **Fecha:** 2026-08-10

## Contexto

El plazo legal de una declaración de enfermedad obligatoria vive en `Task.restriction.period.end`,
que es donde R5 quiere el intervalo dentro del cual se busca cumplir una tarea. La pregunta que
tiene que poder contestarse es «qué declaraciones se han pasado de plazo», y R5 trae **dieciocho**
parámetros de búsqueda estándar para `Task` de los que **ninguno** cae sobre `restriction`: el más
cercano, `period`, cubre `Task.executionPeriod` — cuándo se hizo el trabajo, no hasta cuándo había.

Así que la guía publica el suyo, que es exactamente para lo que una guía de implementación publica
`SearchParameter`. Y como la guía entera está en `status = #draft` —es una simulación en curso—, el
parámetro se escribió en `draft` por coherencia con todo lo demás.

El resultado: el recurso se guarda, se publica en la guía, se lee por la API con un `GET` normal…
y la búsqueda contesta

```
HTTP 400 — HAPI-0524: Unknown search parameter "vencimiento" for resource type "Task"
```

Sin error al escribirlo, sin aviso al arrancar, sin nada en el log. El parámetro **está** y no
funciona.

## Decisión

**Un recurso de conformidad que el servidor tiene que *ejecutar* se publica en `active`, aunque la
guía que lo contiene esté en `draft`.**

Medido sobre HAPI FHIR 8.10.1, con `javap` sobre el JAR:

- `ca.uhn.fhir.jpa.searchparam.registry.SearchParameterCanonicalizer` traduce el `status` del
  recurso a un `RuntimeSearchParamStatusEnum`;
- `ca.uhn.fhir.jpa.searchparam.registry.SearchParamRegistryImpl` se queda **solo con los `ACTIVE`**
  al construir el índice.

Todo lo demás —`draft`, `retired`, `unknown`— se almacena y no se indexa.

La coherencia que se pierde es aparente, y conviene decir por qué: **el `status` de un recurso de
conformidad habla de la madurez de la *definición*, no del proyecto que la contiene.** La
definición de este parámetro está cerrada: `Task.restriction.period.end`, tipo `date`, y no hay nada
que madurar ahí. Que la guía siga creciendo alrededor no la hace provisional. Lo que dice que esto
es una simulación es `experimental = true`, que sigue puesto y es el elemento que existe para eso.

## Consecuencias

- El `SearchParameter` de la guía lleva `status = #active` **con un comentario de seis líneas al
  lado**, porque leído sin contexto parece una incoherencia con el resto de la guía y lo primero que
  haría alguien es «arreglarlo».
- **Vale para el `SubscriptionTopic` también, y para cualquier otro artefacto que el servidor
  ejecute.** La regla no es sobre `SearchParameter`: es sobre la diferencia entre un recurso que se
  publica para que alguien lo lea y uno que se publica para que un servidor lo obedezca.
- El backend lleva su copia en `resources/conformidad/`, y `ci-ig` compara las dos y falla si
  divergen. El `status` viaja en esa copia, así que el gate cubre también este ADR: cambiar el FSH a
  `draft` y no copiar rompería la CI antes de romper la búsqueda.
- Queda una comprobación pendiente barata: **si algún día el registro avisara** de los parámetros
  que ignora, esto dejaría de ser una trampa. Hoy no avisa.

## Alternativas consideradas

- **Dejarlo en `draft` y buscar las declaraciones vencidas leyéndolas todas y filtrando en el
  cliente.** Descartada, y no por rendimiento: una lista de lo vencido que hay que construir a mano
  es una lista que nadie mira. El parámetro existe para que la pregunta sea una llamada.
- **Dejarlo en `draft` y forzar el índice desde Java** —dar de alta el `RuntimeSearchParam` a mano
  en el registro—. Descartada: sería tener la `expression` escrita en dos sitios, uno de los cuales
  no vale, que es exactamente lo que `ci-ig` existe para impedir. Además esconde el problema para el
  siguiente parámetro.
- **Poner la guía entera en `active`.** Descartada: no es verdad. La guía está en desarrollo y su
  `status` lo dice bien; lo que estaba mal era heredarlo mecánicamente en un artefacto ejecutable.

## Lo reutilizable

**Hay recursos que se publican para leer y recursos que se publican para obedecer, y el `status` no
significa lo mismo en los dos.** En los primeros describe la madurez del documento; en los segundos
es un interruptor de encendido que el servidor mira.

El síntoma es de la peor familia que hay: **el recurso valida, el servidor lo acepta con `200`, la
guía lo renderiza — y el comportamiento sencillamente no existe.** Es la misma forma que ADR-0028
(la condición que el servidor no sirve) y que ADR-0020 (la cadena de seguridad que no empareja):
tres veces ya que algo se declara bien, no da error, y no hace nada. La defensa contra esta familia
es una sola y es siempre la misma: **un test que ejercite el comportamiento de punta a punta**, no
que compruebe que el recurso está publicado. Aquí es `loVencidoSeVe`, que hace la búsqueda de verdad
contra el servidor y exige encontrar la declaración vencida.
