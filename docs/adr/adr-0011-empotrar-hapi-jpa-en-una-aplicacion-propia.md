---
tipo: referencia
stack: [java, spring]
aplica_a: [backend]
revisado: 2026-08-03
tags: [adr, hapi-fhir, spring-boot, jpa, toolchain, dependencias, arranque]
---

# ADR-0011: Empotrar el servidor JPA de HAPI, y las siete trampas de hacerlo

- **Estado:** aceptado
- **Fecha:** 2026-08-03

## Contexto

HAPI FHIR se despliega normalmente como **`hapi-fhir-jpaserver-starter`**, que es una *aplicación
completa*: se clona, se configura por `application.yaml` y se arranca. Es lo que recomienda su
documentación y lo que asume todo el material que hay escrito.

Aquí no sirve. La arquitectura de este proyecto (ADR-0002, §9 del diseño) exige que la proyección
FHIR se escriba en la **misma transacción** que el núcleo de dominio, y eso obliga a que las DAO de
HAPI y el dominio compartan el gestor de transacciones **del mismo contexto de Spring**. Con el
starter serían dos aplicaciones y dos transacciones, que es exactamente lo que la arquitectura
descarta.

Así que hay que empotrar `hapi-fhir-jpaserver-base` en la aplicación propia. Lo que el starter
esconde, hay que escribirlo. Y lo que esconde es más de lo que parece: **siete obstáculos, ninguno de
los cuales falla al compilar y ninguno de los cuales da un mensaje que nombre su causa.**

## Decisión

Se empotra el servidor JPA en la aplicación, con una única clase de configuración
(`ConfiguracionServidorFhir`) que hace de *composition root* del borde. Estas son las siete cosas que
hay que resolver, en el orden en que aparecen al arrancar:

### 1. Seis `@Configuration` que importar, no una

`JpaR5Config` no basta, aunque sea la que lleva el nombre de la versión. Hacen falta también:

| Configuración | Qué aporta | Por qué es obligatoria |
|---|---|---|
| `JpaR5Config` | contexto R5, DAO, proveedores de recurso | es el servidor |
| `HapiJpaConfig` | paginación, borrado de búsquedas caducadas | el starter la reimplementa; importarla ahorra ese código |
| `ThreadPoolFactoryConfig` | `ThreadPoolFactory` | lo pide `transactionProcessor` |
| `JpaBatch2Config` | motor de trabajos por lotes | lo pide `IJobMaintenanceService` |
| `Batch2JobsConfig` | trabajos concretos | lo pide `UrlPartitioner`, vía `deleteExpungeJobSubmitter` |
| `SubscriptionChannelConfig` | bus en memoria (`IBrokerClient`) | lo usa `batch2`, no solo `Subscription` |

Las cuatro últimas sostienen funciones que **están fuera del alcance** (lotes, exportación,
suscripciones) y aun así son obligatorias para que el contexto levante. Se descubren de una en una,
a un arranque fallido por cada una.

### 2. Beans propios que nadie te dice que faltan

`JpaStorageSettings`, `PartitionSettings` y `SubscriptionSettings` los define el starter a partir de
su propio `AppProperties`. Sin él hay que declararlos, aunque sea con sus valores por defecto.

### 3. El `EntityManagerFactory` no puede ser el de Spring Boot

Lo construye `HapiEntityManagerFactoryUtil.newEntityManagerFactory(...)`, porque HAPI registra sus
propias entidades. El bean propio desplaza al de Boot por `@ConditionalOnMissingBean`.

### 4. El dialecto hay que declararlo, no deducirlo

Sin `hibernate.dialect` explícito, el arranque muere con:

```
NullPointerException: Unable to create instance of class: null
```

que no menciona ni el dialecto ni la base de datos. HAPI pide el dialecto **antes** de que haya
conexión de la que deducirlo. Y el valor correcto no es el genérico de Hibernate sino el de HAPI
(`HapiFhirPostgresDialect`), que ajusta tipos y funciones de su esquema.

### 5. `allow-circular-references: true`

El grafo de beans de HAPI tiene un ciclo propio entre `searchParamRegistry` e
`inMemoryResourceMatcher`. Spring Boot prohíbe los ciclos desde la 2.6 —y hace bien—, así que hay que
levantar la prohibición **para toda la aplicación**. Es el precio, y conviene dejar escrito que la
excepción es de HAPI y no una licencia para el código propio.

### 6. Tres autoconfiguraciones que sobran, y una que falta

Estar en el *classpath* basta para que Spring Boot configure cosas que nadie pidió:

- **Elasticsearch.** HAPI arrastra su cliente REST y Boot lo apunta a `localhost:9200`. El resultado
  es un hilo reintentando conectarse eternamente y llenando el log de `ERROR` que no lo son. Hay que
  excluir las autoconfiguraciones a mano.
- **Hibernate Search.** Se apaga con `HibernateOrmMapperSettings.ENABLED = false`, o el arranque
  falla por no encontrar motor de indexación.
- **Flyway.** Llega transitivo y se autoconfigura; sin `flyway-database-postgresql` aborta con
  *«Unsupported Database: PostgreSQL 14.15»*, culpando a la base de datos en vez de al módulo que
  falta.

### 7. Spring Boot degrada una librería que HAPI necesita

`spring-boot-starter-parent` 3.5.16 gestiona `commons-lang3` en la **3.17.0**; HAPI 8.10.1 usa
`ObjectUtils.getIfNull(T, T)`, que aparece en la **3.18.0**. Gana el `dependencyManagement` del
padre, en silencio.

**Compila perfectamente.** El conflicto estalla al servir la primera petición, con un
`NoSuchMethodError` y un `500` — es decir, cuando ya crees que está funcionando. Se corrige subiendo
la propiedad `commons-lang3.version` en el `pom.xml` propio.

## Consecuencias

- **La configuración es nuestra y hay que mantenerla.** Cada actualización de HAPI puede añadir un
  bean obligatorio nuevo; el síntoma será un `NoSuchBeanDefinitionException` al arrancar. Es el coste
  de no usar el starter, y se paga a cambio de la transacción única, que es el punto entero de la
  arquitectura.
- **El `pom.xml` fija una versión por encima de la que gestiona Spring Boot.** Hay que revisarla al
  actualizar Boot y quitarla cuando ya alcance.
- **Todo esto se descubre arrancando, no compilando.** Por eso el arnés de test levanta la aplicación
  entera contra una base de datos real desde el primer ítem del backend: un test que no arranca el
  contexto no habría detectado ni uno de los siete.

## Alternativas consideradas

- **Usar `hapi-fhir-jpaserver-starter` como aplicación aparte** — descartado por la razón que abre
  este documento: rompe la transacción única y con ella el *read-your-writes* de §9. Es la
  alternativa cómoda y la que casi todo el material asume.
- **Copiar `StarterJpaConfig` entero al proyecto** — trae `AppProperties`, MDM, CDS Hooks, CQL,
  websockets y validación por repositorio: unas 670 líneas de las que aquí sobran las nueve décimas.
  Sí se ha usado **como referencia** para saber qué beans existen, que es distinto de heredar su peso.
- **Renunciar a HAPI JPA y escribir la persistencia FHIR a mano** — se pierden búsqueda por
  `SearchParameter`, `_include`, paginación por `Bundle.link`, `_history` y `ETag`, que son justo lo
  que los criterios de aceptación 7, 8 y 11 exigen. No es una alternativa, es rehacer HAPI.
