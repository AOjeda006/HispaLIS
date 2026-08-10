---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-10
tags: [adr, fhir, r5, terminologia, conceptmap, hapi, portabilidad]
---

# ADR-0028: Una regla condicionada no se publica donde el servidor no la sirve

- **Estado:** aceptado
- **Fecha:** 2026-08-10

## Contexto

Hacía falta publicar una regla de dos mitades: **qué enfermedad de declaración obligatoria confirma
una prueba de laboratorio** y **con qué resultado la confirma**. Un antígeno de Legionella en orina
declara una legionelosis, pero solo si sale positivo; un negativo no declara nada, y un indeterminado
tampoco.

FHIR R5 tiene un elemento hecho exactamente para esto, y es fácil de encontrar buscando: la
combinación de `ConceptMap.additionalAttribute` —que declara un atributo con su tipo— y
`ConceptMap.group.element.target.dependsOn`, que dice *«este mapeo solo es aplicable si el atributo
tal tiene el valor cual»*. Es además una de las cosas que R5 renombró: en R4 el elemento era
`dependsOn.property`, de tipo `uri`, y `additionalAttribute` no existía. La operación `$translate`
lo lleva en las dos direcciones — `dependency` a la entrada y `match.dependsOn` a la salida.

Sobre el papel, la decisión estaba tomada: la regla es un mapeo condicionado, y el estándar tiene un
mapeo condicionado.

## Decisión

**Antes de elegir dónde publicar una regla, comprobar que el servidor que la va a servir sabe
servirla.** Aquí no sabía, y la regla se publicó en otro sitio.

Medido sobre HAPI FHIR 8.10.1: la clase `ca.uhn.fhir.jpa.api.model.TranslationQuery` **no tiene
`dependency`**, y su `TranslationRequest` tampoco. El `match` que devuelve `$translate` no trae
`dependsOn`. Es decir: el servidor no acepta la condición a la entrada ni la comunica a la salida.

Las dos mitades de la regla pasaron a ser **propiedades del concepto** en el `CodeSystem` del
catálogo local, que es lo que `$lookup` sí devuelve entero y en un solo viaje.

## Consecuencias

- **Una sola fuente de verdad.** Es el argumento que decide, y no la comodidad. Publicar la
  enfermedad en el `ConceptMap` y el criterio en otra parte habría partido en dos una regla que solo
  significa algo junta, con lo que eso trae: que alguien cambie una mitad, que las dos digan cosas
  incompatibles, y que nada lo detecte.
- **Un solo `$lookup`** trae la enfermedad y el criterio a la vez, dentro de la transacción del
  dominio. Con el mapa habrían sido dos viajes como mínimo.
- **Coherencia con lo que ya había.** El umbral crítico y la regla de prueba refleja de este mismo
  proyecto ya viven como propiedades del concepto, por razones distintas pero compatibles. Un tercer
  mecanismo para la tercera regla habría sido lo caro de mantener.
- **Se pierde expresividad estándar.** Un consumidor externo que sí implemente `dependsOn` no puede
  descubrir esta regla con `$translate`: tiene que leer la documentación de la guía y hacer
  `$lookup`. Es el precio, y se paga a sabiendas.
- **Queda una fecha de caducidad.** El día que se cambie de servidor de terminología, esto es lo
  primero que conviene volver a medir: si el nuevo sí lo sirve, el `ConceptMap` vuelve a ser el sitio
  correcto.

## Alternativas consideradas

- **Publicarlo en el `ConceptMap` de todas formas, «porque es lo correcto según el estándar», y que
  el backend leyera el criterio de otro sitio.** Descartada: son dos fuentes de verdad para una regla
  cuyas mitades no significan nada por separado. Publicar en el estándar algo que el sistema no usa
  es peor que no publicarlo — hace creer que la regla está ahí.
- **Publicarlo en el `ConceptMap` y que el backend se descargue el recurso entero y lo interprete
  él.** Descartada por dos razones. Convierte al laboratorio en intérprete de mapas —justo lo que
  evita delegar en un servicio de terminología— y le pone delante la tentación de guardarse el mapa
  en memoria, que es la lista paralela que este proyecto prohíbe.
- **Inventarse una extensión propia sobre `ConceptMap`.** Descartada de entrada: la extensión no
  sería más servible que el elemento estándar que el servidor ya ignora, y añadiría una URI propia
  para expresar algo que el estándar sabe decir.

## Lo reutilizable

Un estándar tiene dos superficies y no coinciden: **lo que el modelo puede expresar** y **lo que las
implementaciones sirven**. Al elegir dónde vive una regla hay que mirar las dos, y mirar la segunda
cuesta diez minutos de `javap` sobre el JAR del servidor.

El síntoma de haberlo hecho mal es silencioso, que es lo que lo hace caro: el recurso valida, la guía
publica, el servidor contesta `200` — y la condición sencillamente no viaja. Nada falla; la regla
simplemente no se aplica.
