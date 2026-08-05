---
tipo: referencia
stack: [java, spring]
aplica_a: [backend]
revisado: 2026-08-05
tags: [adr, transacciones, hapi-fhir, spring, jdbc, jpa, arquitectura]
---

# ADR-0012: Cómo se consigue —de verdad— una sola transacción entre el dominio y la proyección

- **Estado:** aceptado
- **Fecha:** 2026-08-05

## Contexto

La arquitectura de este proyecto (ADR-0002, §9 del diseño) se sostiene sobre una afirmación muy
concreta: **el dominio y su proyección FHIR se escriben en la misma transacción**. De ahí sale el
*read-your-writes* que FHIR REST exige —un `GET` inmediato al `Location` de un `201` tiene que
devolver el recurso— y de ahí sale que un rechazo del dominio no deje un recurso FHIR huérfano al
que nada respalda.

El problema es que **«están en la misma transacción» es fácil de creer y difícil de notar cuando es
falso**. Los dos escritores conviven en el mismo contexto de Spring, comparten `DataSource`, ambos
están bajo un `@Transactional`, y en el camino feliz todo funciona igual esté bien o mal. La
diferencia solo aparece cuando algo falla: con dos transacciones, la proyección se confirma con el
dominio revertido y el sistema queda mintiendo, sin un solo error en el log.

Se añade una asimetría propia de este montaje: el `EntityManagerFactory` **es de HAPI**, no nuestro
(ADR-0011). El dominio no puede simplemente meter sus entidades dentro.

## Decisión

El dominio persiste con **SQL explícito** (`NamedParameterJdbcTemplate`) sobre el esquema
`dominio`, y la proyección con las **DAO de HAPI** sobre el suyo. Los une el mismo gestor de
transacciones. Cuatro cosas hay que hacer bien, y ninguna avisa cuando se hace mal:

### 1. `JpaTransactionManager.setDataSource(...)` — la línea de la que depende todo

```java
JpaTransactionManager gestor = new JpaTransactionManager();
gestor.setEntityManagerFactory(fabricaDeEntidades);
gestor.setDataSource(origenDeDatos);   // ← sin esto, son dos transacciones
```

Sin ella, el `JdbcTemplate` pide una conexión nueva al `DataSource` en vez de tomar la que la
transacción JPA ya tiene abierta. **Todo compila, todo arranca y el camino feliz pasa los tests.**
Solo se rompe al fallar algo, que es justo cuando importa.

Por eso el criterio se prueba **por el lado del fallo**: se da de alta dos veces el mismo NHC y se
comprueba que el rechazo del dominio no deja detrás un `Patient`. Un test del camino feliz no
distingue una transacción de dos.

### 2. Por qué SQL y no Spring Data JPA, que es la convención

Meter las entidades del dominio en el `EntityManagerFactory` de HAPI obliga a reproducir a mano la
lista de paquetes que HAPI escanea (`ca.uhn.fhir.jpa.model.entity`, `ca.uhn.fhir.jpa.entity`), que
es interna suya y puede crecer en cualquier versión menor —y si crece, las entidades que falten
desaparecen en silencio—.

A cambio de renunciar a JPA aquí no se pierde nada que este agregado necesite, y se gana que quede
**libre de anotaciones de persistencia**, que es lo que Clean Architecture pide de un núcleo. La
convención de Spring Data sigue valiendo donde el `EntityManagerFactory` sea nuestro.

### 3. El proveedor propio sustituye al de HAPI, y hay que enlazarlo a su DAO

Registrar dos proveedores para el mismo recurso es un error de arranque, así que el nuestro no se
añade: **reemplaza** al que fabrica HAPI, filtrando por tipo de recurso.

Y como es un bean de Spring, la fábrica de HAPI **no le inyecta su DAO**: hay que llamar a
`setDao(...)` al registrarlo. Si se olvida, la creación funciona —va por nuestro camino— y todo lo
heredado (leer, buscar, `_history`) revienta con un `NullPointerException`. El fallo aparece lejos
de su causa.

Conviene además heredar del proveedor de HAPI y **sobrescribir solo la creación**: la lectura, la
búsqueda por `SearchParameter`, la paginación por `Bundle.link` y el `ETag`/`If-Match` vienen
gratis, y son criterios de aceptación del propio proyecto.

### 4. Pedir `List<IResourceProvider>` a Spring rompe el arranque

Inyectar la interfaz de HAPI obliga a Spring a **instanciar todos los beans de ese tipo**, incluidos
proveedores internos suyos que solo se pueden construir con funciones fuera de alcance activadas. El
contexto muere con un `NoSuchBeanDefinitionException` de algo que nadie pidió.

Se resuelve con una interfaz propia (`ProveedorPropio extends IResourceProvider`) que acota la
inyección a los nuestros.

### 5. Los errores del dominio llegan envueltos

El dominio lanza excepciones que no saben nada de HTTP, y traducirlas al código correcto es trabajo
del borde. La trampa está en **dónde** se traduce: cuando la petición llega al *pointcut*
`SERVER_PRE_PROCESS_OUTGOING_EXCEPTION`, HAPI **ya ha envuelto** lo que lanzó el proveedor en un
`InternalErrorException` con el prefijo `HAPI-0389: Failed to call access method`.

Comprobar el tipo de la excepción recibida no encuentra nunca nada. Hay que **recorrer la cadena de
causas**. El síntoma de no hacerlo es especialmente engañoso: sale un `500` **con el mensaje del
dominio dentro**, que parece que el interceptor funciona y solo falla el código.

## Consecuencias

- **La transacción única deja de ser una afirmación y pasa a ser algo verificado.** El test del NHC
  duplicado es el que la sostiene; si alguien quita el `setDataSource`, ese test se pone rojo.
- **El agregado no depende de ningún framework de persistencia.** El precio es escribir el SQL y el
  mapeo de filas a mano, que para un agregado de este tamaño es menos código que la alternativa.
- **Cada recurso con escritura por el dominio necesita su proveedor propio**, y el patrón está fijado:
  heredar, sobrescribir la creación, implementar `ProveedorPropio`. Los siguientes salen baratos.
- **La traducción de errores está en un solo sitio.** Añadir un tipo de error de dominio es añadir
  una rama, no repetir un `try`/`catch` en cada proveedor.

## Alternativas consideradas

- **Escribir la proyección con un interceptor de HAPI** (`STORAGE_PRESTORAGE_RESOURCE_CREATED`) en vez
  de con un proveedor propio: sería menos código, pero guardaría **el recurso que llegó** en vez de
  uno generado desde el dominio. Entonces no sería una proyección: el dominio y lo publicado podrían
  decir cosas distintas, y el reconciliador previsto en el hito 2 no tendría de dónde regenerar nada.
- **Dos `EntityManagerFactory`, uno nuestro y otro de HAPI**, sobre el mismo `DataSource` — necesita
  JTA o una coreografía de *flush* frágil para compartir transacción. Más piezas para el mismo
  resultado.
- **Proyección asíncrona por eventos**, que es lo natural en CQRS — descartado en el diseño y aquí se
  confirma por qué: el `GET` inmediato daría `404` y eso no es una latencia aceptable, es incumplir
  FHIR REST.
- **Confiar en que el camino feliz pruebe la transacción** — no la prueba. Es exactamente el error que
  este ADR existe para evitar.
