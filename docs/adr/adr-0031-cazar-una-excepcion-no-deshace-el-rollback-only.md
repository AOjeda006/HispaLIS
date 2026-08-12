---
tipo: referencia
stack: [java, spring]
aplica_a: []
revisado: 2026-08-10
tags: [adr, spring, transacciones, hapi, rollback-only, diagnostico]
---

# ADR-0031: Cazar la excepción no deshace el `rollback-only`

- **Estado:** aceptado
- **Fecha:** 2026-08-10

## Contexto

Al declarar una enfermedad obligatoria, el caso entra en la cohorte de vigilancia de esa enfermedad
(ítem 49). La cohorte es un `Group` con id calculable, así que el código hacía lo evidente: leerla y,
si no está, crearla.

```java
try {
    return daos.getResourceDao(Group.class).read(new IdType("Group", id), new SystemRequestDetails());
} catch (ResourceNotFoundException primeraVez) {
    return traductor.nueva(codigo, nombre);      // la primera declaración de esa enfermedad
}
```

Funciona. Se crea la cohorte, se le añade el miembro, y el log lo confirma:

```
INFO  ApuntarEnLaCohorte : Cohorte de vigilancia LEGIONELOSIS: un caso más, 1 en total.
WARN  NotificadorEdo     : La vuelta del notificador EDO ha fallado entera; se reintenta.
                           Causa: UnexpectedRollbackException: Transaction silently rolled back
                           because it has been marked as rollback-only
```

Y en la base de datos no hay nada: ni cohorte, ni declaración, ni el desplazamiento del notificador
avanzado. El bucle se repetía cada 200 ms, diciendo cada vez «un caso más, 1 en total».

**La causa:** `IFhirResourceDao.read` es `@Transactional`. Al participar en la transacción del caso de
uso y lanzar una excepción, Spring marca **toda** la transacción como *rollback-only* antes de que la
excepción llegue al `catch`. Capturarla evita que se propague; **no deshace la marca**. El código
sigue adelante como si nada, hace su trabajo, y al confirmar salta un `UnexpectedRollbackException`
en un sitio que no tiene ninguna relación visible con la línea que lo provocó.

Lo caro de este fallo no es el fallo: es el diagnóstico. Todo lo que se ve es correcto —el log dice
que funcionó, no hay excepción en el `catch`, el `read` es una lectura y las lecturas no parecen
peligrosas— y el error aparece al final, sin traza que apunte a la causa.

## Decisión

**Dentro de una transacción, «¿existe esto?» se pregunta buscando, no leyendo y cazando.**

```java
SearchParameterMap porId = SearchParameterMap.newSynchronous().add("_id", new TokenParam(id));
return daos.getResourceDao(Group.class).search(porId, new SystemRequestDetails())
        .getAllResources().stream().map(Group.class::cast).findFirst()
        .orElseGet(() -> traductor.nueva(codigo, nombre));
```

Una búsqueda devuelve el conjunto vacío. No lanza, no ensucia la transacción, y expresa mejor lo que
se quería decir: no es un error que la cohorte no exista todavía.

## Consecuencias

- El notificador EDO abre la declaración y la cohorte en la misma transacción, que es lo que se
  pretendía desde el principio.
- Una búsqueda por `_id` cuesta algo más que un `read`. En el camino de una declaración —que ocurre
  unas cuantas veces al día— es irrelevante.
- Queda la regla escrita en el javadoc del método, en el sitio donde alguien podría volver a
  «simplificarlo» a un `read`.

## Alternativas descartadas

- **`@Transactional(noRollbackFor = ResourceNotFoundException.class)`** en el caso de uso. No sirve:
  quien marca la transacción es el proxy del método interior, no el del exterior, y la anotación del
  exterior no gobierna eso.
- **`Propagation.REQUIRES_NEW`** alrededor del `read`. Aísla el problema, pero abre una conexión más
  por cada comprobación y añade un punto de suspensión de transacción para preguntar si algo existe.
  Desproporcionado.
- **Comprobar primero con SQL directo.** Rompería el único camino de escritura y de lectura de la
  proyección, que pasa por las DAO de HAPI.

## Lo reutilizable

Vale para **cualquier** código Spring, no solo para HAPI:

1. **Un `catch` no limpia la transacción.** Si el método que lanzó es `@Transactional` y participaba
   en la tuya, la transacción ya está condenada cuando tú te enteras. La regla práctica: dentro de una
   transacción, no uses excepciones de una llamada transaccional como flujo de control.
2. **El síntoma aparece lejos de la causa.** `UnexpectedRollbackException` al confirmar, con el log
   diciendo que todo fue bien. Cuando aparezca, lo que hay que buscar es un `catch` sobre una llamada
   transaccional, y no la línea que confirma.
3. **«¿Existe?» tiene dos formas y no son equivalentes.** La que lanza y la que devuelve vacío. Fuera
   de una transacción da igual cuál uses; dentro, solo una es correcta.
