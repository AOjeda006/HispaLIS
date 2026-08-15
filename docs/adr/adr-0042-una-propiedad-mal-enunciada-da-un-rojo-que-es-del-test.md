---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-15
tags: [adr, testing, property-based, invariantes, idempotencia, generadores]
---

# ADR-0042: Una propiedad mal enunciada da un rojo que es del test

- **Estado:** aceptado
- **Fecha:** 2026-08-15

## Contexto

Cuatro propiedades escritas sobre los canales de entrada, con nombres generados —dobles apellidos,
partículas, `Ñ`, tildes y `ç`—. Tres salieron verdes a la primera. La cuarta, la **idempotencia del
reproceso**, salió roja en los cinco casos de un canal, con una escritura de más por cada reproceso.

No era un fallo del código. Ese canal busca al paciente y, si lo encuentra, **lo corrige**; los otros
dos se saltan lo que ya existe. Y está bien que lo corrija: el evento que procesa *es* una
corrección de datos demográficos. Lo que estaba mal era la propiedad. **«Idempotente» dice que el
estado final no cambia, no que no se toque el disco**, y la afirmación escrita contaba escrituras.

Ahí había dos salidas fáciles y las dos empeoran el sistema:

- **Tocar el código** para que el canal deje de corregir. Rompe un comportamiento correcto para que
  pase un test mal escrito.
- **Debilitar la propiedad** hasta que pase: afirmarla solo sobre los dos canales clínicos, o contar
  escrituras solo en el alta. La suite se pone verde y afirma menos que antes de empezar.

Apareció además un candidato a quinta propiedad que **no se escribió, y por una razón que vale más
que la propiedad**: la sustitución de caracteres de la tarjeta sanitaria —donde `ñ` viaja como `$`,
de modo que `MUÑOZ` sale `MU$OZ`— es un caso de libro para `deshacer ∘ aplicar == identidad`. Es real,
está documentada, y **no existe en este sistema**: no hay lector, no hay banda magnética y no hay una
sola línea que sustituya esos caracteres. Escribir la propiedad habría exigido escribir antes la
función, para tener qué probar.

## Decisión

1. **Un rojo de property-based se diagnostica antes de arreglarse**, y la primera pregunta es si la
   propiedad dice lo que se cree. Aquí la respuesta fue que no.
2. **Una propiedad se puede reenunciar solo si la nueva afirma más.** La de idempotencia pasó de «el
   número de escrituras no crece» a **«el estado final del laboratorio entero, comparado byte a
   byte»**: estrictamente más fuerte, y esta vez sobre idempotencia de verdad. Si la reformulación
   afirma menos, no se ha entendido el sistema: se ha silenciado lo único que lo estaba diciendo.
3. **El enunciado corregido se escribe también en el código que prometía de más.** El javadoc del
   reproceso decía lo mismo que decía la propiedad equivocada, y se corrigió en el mismo movimiento:
   una propiedad mal enunciada casi siempre está mal documentada en el sitio donde nace.
4. **No se escribe una propiedad sobre una función que el sistema no tiene.** Se elige la
   transformación que el sistema **sí** hace —aquí, el juego de caracteres declarado dentro del propio
   mensaje— y esa es la que recibe entradas generadas.
5. **Los generadores son propios y no reducen al contraejemplo mínimo**, y el precio se paga en otra
   moneda: semilla fija e impresa, un test con nombre por caso y el contraejemplo renderizado entero
   en el fallo.

## Consecuencias

- La propiedad de idempotencia es ahora mucho más cara de ejecutar —compara el estado completo— y
  mucho más difícil de engañar. Es el intercambio correcto: una propiedad barata que no afirma nada
  cuesta más que no tenerla, porque ocupa el hueco.
- Tres verdes a la primera **se anotan como resultado**. No dicen que el código de hoy esté bien: dicen
  que esos tres invariantes estaban bien entendidos, y dejan escrita la afirmación que se romperá el
  día que alguien los cambie.
- Sin *shrinking*, un contraejemplo grande se lee entero. Se acepta porque las entradas de este
  dominio caben en una línea; con entradas estructuradas y profundas la cuenta sale al revés y hay que
  traer la herramienta.
- La propiedad descartada queda descartada **por escrito**, con el motivo. Si no, alguien la propone
  otra vez dentro de seis meses y la discusión se repite desde el principio.

## Alternativas consideradas

- **Traer una librería de property-based solo por el *shrinking*.** Una dependencia de test más en un
  módulo, para reducir entradas que ya son legibles. Se revisará el día que haya entradas que no lo
  sean.
- **Marcar la propiedad de idempotencia como conocida-roja y seguir.** Es la peor de todas: deja en la
  suite una afirmación falsa con permiso.
- **Aceptar la propiedad débil** (idempotencia solo donde ya se cumplía). Habría dejado sin afirmar
  justo el canal que hace algo distinto, que es el que la merecía.
- **Escribir la sustitución de caracteres de la tarjeta para poder probarla.** Código de producción
  nuevo, sin un solo consumidor, cuya única razón de existir sería tener sujeto para una propiedad
  bonita.

## Lo reutilizable

1. **Un rojo de property-based es, muchas veces, del enunciado.** El reflejo es tocar el código; la
   primera pregunta es qué afirma exactamente la propiedad. «Idempotente» es la palabra que más se
   enuncia mal: dice que el estado final no cambia, no que no se escriba.
2. **Reenunciar una propiedad solo vale si la nueva afirma más.** Debilitarla para verla en verde es
   silenciar la única herramienta que estaba señalando algo.
3. **No escribas la función para poder probarla.** El catálogo de propiedades canónicas —ida y vuelta,
   idempotencia, orden— invita a buscar en el sistema una transformación que encaje, y la que encaja
   puede no existir. Elegir la que el sistema hace de verdad es el trabajo.
4. **Que una propiedad salga verde a la primera es un resultado y se anota.** Mide que el invariante
   estaba bien entendido, y deja puesta la red para cuando alguien lo cambie.
5. **El *shrinking* se puede sustituir, no ignorar.** Sin él hay que comprar la reproducibilidad en
   otro sitio: semilla fija e impresa, un caso por test con nombre propio y el contraejemplo completo
   en el fallo. Lo que no vale es un rojo que diga «falló con una de las treinta entradas».
