---
tipo: referencia
stack: []
aplica_a: [ig]
revisado: 2026-08-03
tags: [adr, fhir, terminologia, loinc, snomed, validacion, i18n, locale]
---

# ADR-0009: No se fija a mano el `display` de un código de terminología externa

- **Estado:** aceptado
- **Fecha:** 2026-08-03

## Contexto

Un `Coding` lleva tres cosas: `system`, `code` y `display`. Las dos primeras son el dato; la tercera
es la etiqueta legible. Parece inofensivo escribirla, y toda la documentación de FHIR la incluye en
sus ejemplos.

En este proyecto se escribió `$LOINC#11502-2` con `display = "Laboratory report"`. Ese texto **no
está inventado**: es literalmente el `LONG_COMMON_NAME` que trae la tabla Core de LOINC 2.82,
comprobado contra el fichero. Aun así, el validador oficial lo rechazó:

```
DiagnosticReport.code.coding[0].display: Error - Wrong Display Name 'Laboratory report'
for http://loinc.org#11502-2. Valid display is one of 2 choices: 'Reporte de laboratorio: …'
```

La causa no es el código ni el nombre, sino **el idioma**: el validador arranca con el locale del
sistema —aquí `España/ES`—, se lo pasa al servidor de terminología, y `tx.fhir.org` responde con la
variante lingüística española de LOINC. El nombre en inglés deja de ser válido.

La consecuencia es la peor posible para un equipo: **el mismo recurso, sin tocar una coma, valida en
un runner de CI en inglés y falla en el portátil de quien lo escribió**. El error no menciona el
idioma por ninguna parte, así que se investiga el código LOINC, que está bien.

El problema es más amplio que LOINC. Afecta a **toda terminología externa cuyo servidor sirva
traducciones**: SNOMED CT con sus ediciones nacionales es el caso obvio —una edición española
devuelve términos distintos de la internacional para el mismo `conceptId`—, y este proyecto usa
precisamente las dos.

## Decisión

**En los artefactos de esta guía, un `Coding` de terminología externa lleva `system` y `code`, y no
lleva `display`.** El término lo resuelve el servidor de terminología en el momento de mostrarlo.

Se mantiene el `display` en dos sitios, y solo en dos:

1. **En los códigos del `CodeSystem` propio** (`catalogo-pruebas`), porque de ese sistema somos la
   autoridad: el `display` no es una copia de nada, es el original.
2. **En `ConceptMap.group.element.target.display`**, que es documentación del mapeo y no un `Coding`
   sujeto a validación. Ahí se conserva el nombre oficial de LOINC **sin alterar**, que es lo que su
   licencia exige.

## Consecuencias

- **Los recursos dejan de depender del idioma de quien los valida.** Es el objetivo.
- **La guía publicada muestra los términos que sirva el servidor configurado.** Mientras se resuelva
  contra la edición internacional de SNOMED, saldrán en inglés; pasarán a español al cargar la
  Edición Española del SNS. Eso es una propiedad del despliegue, no del contenido, que es
  exactamente donde debe estar.
- **Se pierde legibilidad del JSON en crudo.** Un `Coding` sin `display` obliga a consultar el
  servidor para saber qué es. Es el precio, y se paga a gusto: un `display` congelado envejece mal y
  además **no se puede comparar ni enrutar por él**, así que su valor era solo cosmético.
- **La regla no aplica al texto que ve el usuario.** `CodeableConcept.text` sigue siendo el sitio
  correcto para lo que el laboratorio quiere que se lea, y no se valida contra el servidor.

## Alternativas consideradas

- **Fijar el locale del validador** (`-locale en`) para que todos obtengan el mismo resultado — se
  descartó: esconde el problema detrás de un parámetro que hay que recordar poner en cada invocación,
  y no arregla el recurso, que sigue llevando un `display` de un idioma concreto.
- **Escribir el `display` en español** — peor: fallaría en la CI, que corre en inglés, y ataría el
  contenido a que el servidor tenga cargada la variante española.
- **Desactivar la comprobación de `display`** (`-display-issues-are-warnings`) — convierte en aviso
  un error que en otros casos sí señala un `Coding` mal copiado. Se pierde una comprobación útil para
  tapar un problema que tiene arreglo limpio.
- **Copiar el `display` del fichero oficial de LOINC**, que es lo que se hizo — no basta, y este ADR
  existe porque no bastó: el nombre era correcto y el recurso no validaba igualmente.
