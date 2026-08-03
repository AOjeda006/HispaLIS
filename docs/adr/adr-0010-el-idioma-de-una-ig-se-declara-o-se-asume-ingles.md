---
tipo: referencia
stack: []
aplica_a: [ig]
revisado: 2026-08-03
tags: [adr, fhir, ig-publisher, i18n, idioma, accesibilidad, publicacion]
---

# ADR-0010: El idioma de una guía se declara, o el publisher lo asume inglés

- **Estado:** aceptado
- **Fecha:** 2026-08-03

## Contexto

Esta guía está escrita entera en castellano: descripciones, narrativas, `display` del catálogo
propio, portada. La configuración de SUSHI no decía en ningún sitio en qué idioma estaba, porque no
parecía que hiciera falta decirlo.

Al publicarla por primera vez en GitHub Pages, la raíz del sitio resultó ser esto:

```html
<html><body>
<script type="text/javascript">
langs=["en"]
</script>
<script type="text/javascript" src="assets/js/lang-redirects.js"></script>
</body></html>
```

548 bytes. El IG Publisher genera la salida **repartida por idioma**: las páginas reales cuelgan de
una carpeta por idioma y en la raíz deja un *stub* de JavaScript que redirige a la que corresponda
al navegador. Sin ninguna declaración de idioma, el idioma que asume es **inglés**: las páginas
salieron bajo `/en/`, etiquetadas `<html lang="en">` y con los rótulos de la plantilla en inglés.

**Lo grave no es el fallo, es que no se ve.** La cadena de construcción entera estaba en verde:

| Comprobación | Resultado |
|---|---|
| `sushi .` | 0 errores, 0 warnings |
| IG Publisher | construye sin avisos |
| Validador oficial sobre los recursos | 0 errores |
| QA del publisher | sin hallazgos |

Ninguna herramienta tiene forma de saber que el texto está en un idioma distinto del declarado: para
el validador, una `description` en castellano es una cadena válida. El defecto **solo existe en el
HTML desplegado**, así que solo se encuentra mirando el sitio publicado — o no se encuentra.

Las consecuencias son reales, no cosméticas: un lector de pantalla pronuncia el castellano con
fonética inglesa, los buscadores indexan el contenido con el idioma equivocado, y el navegador
ofrece traducirlo desde un idioma que no es el suyo.

## Decisión

**El idioma de una guía se declara explícitamente, en los dos sitios que hacen cosas distintas**, más
el ámbito:

```yaml
language: es                        # idioma del recurso ImplementationGuide
jurisdiction: urn:iso:std:iso:3166#ES

parameters:
  i18n-default-lang: es             # idioma que asume el publisher al renderizar
  resource-language-policy: all-ig  # cada recurso hereda el idioma de la IG
```

Los dos primeros no son redundantes y no se sustituyen:

- **`i18n-default-lang`** es un parámetro del **publisher**: gobierna el renderizado y el reparto de
  la salida por carpetas de idioma. Su documentación dice literalmente que, si falta, *«no hay idioma
  por defecto — los servicios multi-idioma no están habilitados»*.
- **`language`** es un elemento del **recurso** `ImplementationGuide`, y es de donde
  `resource-language-policy: all-ig` toma el idioma que propaga a `Resource.language` de cada
  recurso. Sin él, la política no tiene de dónde copiar.

**Y la comprobación no es que el build pase, es abrir el sitio publicado** y mirar el atributo `lang`
del `<html>`. Nada anterior al despliegue lo detecta.

## Consecuencias

- **Las páginas cuelgan de `/es/`** y la raíz es un *stub* de redirección. Es el diseño del publisher,
  no algo que se pueda desactivar: cualquier enlace profundo desde fuera debe apuntar a la carpeta de
  idioma o aceptar que la redirección la hace JavaScript. **Un cliente sin JavaScript —un `curl`, un
  rastreador simple— no obtiene nada en la raíz.** Conviene saberlo antes de enlazar la guía.
- **Los rótulos de la plantilla salen traducidos.** `fhir2.base.template` trae la traducción española
  completa (126 + 78 cadenas, sin huecos), así que no hay que aportar ningún `.po` propio. Con un
  idioma peor cubierto habría que revisar la cobertura antes de declararlo.
- **Cada recurso lleva su `Resource.language`**, que es lo correcto cuando la narrativa está en
  castellano y evita que un consumidor tenga que adivinarlo.
- **Declarar la jurisdicción tiene efecto propio:** una guía sin `jurisdiction` se presenta como de
  ámbito internacional. Esta perfila el contexto sanitario español —identificadores del SNS, códigos
  INE, declaración EDO— y no sirve fuera de él.

## Alternativas consideradas

- **Dejar el defecto.** Es lo que estaba, y significa publicar una guía castellana etiquetada como
  inglesa. Descartado: rompe la accesibilidad y contradice la regla del proyecto de que todo lo que
  ve el usuario va en español.
- **Declarar solo `ImplementationGuide.language`.** Es el elemento «obvio» y el que uno toca primero,
  pero por su definición gobierna el recurso, no el renderizado: el reparto de la salida por idioma
  lo decide el parámetro del publisher.
- **Declarar solo `i18n-default-lang`.** Deja `resource-language-policy` sin fuente de la que copiar
  y el propio `ImplementationGuide` sin idioma.
- **Aportar traducciones propias en `.po`.** Innecesario aquí y caro de mantener: la plantilla ya trae
  el castellano completo. Solo tendría sentido en un idioma sin cobertura.
- **Confiar en que la CI lo detecte.** No puede: el defecto no está en los artefactos que valida, sino
  en el HTML que genera después. La comprobación es sobre el sitio desplegado.
