---
tipo: referencia
stack: []
aplica_a: []
revisado: 2026-08-03
tags: [adr, fhir, espana, identificadores, cip-sns, nuhsa, perfilado, uris-canonicas]
---

# ADR-0003: Identificadores españoles — jerarquía, sin `pattern`, y URIs canónicas propias

- **Estado:** aceptado
- **Fecha:** 2026-08-03
- **Decisiones de diseño:** D16 y D19 (`docs/diseno.md` §4.1, §4.8)

## Contexto

En España un paciente no tiene un identificador: tiene varios a la vez, y **no son paralelos**. El
**RD 183/2004** (modificado por el RD 702/2013 y el RD 922/2024) define una jerarquía: el **CIP-SNS**
(16 caracteres, único y vitalicio, emitido por la BDPP-SNS) actúa de **nexo** entre los **CIP-AUT**
autonómicos; el **CITE** identifica a la administración emisora de la tarjeta. **El NUHSA es el
CIP-AUT de Andalucía** — `AN` + 10 dígitos —, no un tipo aparte.

Dos hechos condicionan el perfilado:

1. **El laboratorio no emite ninguno de esos códigos.** Solo emite su propio NHC — que la **Ley
   41/2002** le obliga a asignar, también siendo un centro privado.
2. **Siendo un privado, el NUHSA no es universal entre los pacientes.** Mutualistas (MUFACE, MUGEJU,
   ISFAS) y privados con frecuencia no lo conocen, hasta el punto de que el propio SAS publica un
   procedimiento para averiguarlo.

Además, **España no tiene un juego oficial consolidado de URIs canónicas** para estos `system`.

## Decisión

1. **Modelar la jerarquía, no una lista plana:** el *slicing* de `Patient.identifier` en
   `PacienteLabES` distingue **CIP-SNS / CIP-AUT / NHC**, con el NUHSA como *slice* de tipo "CIP
   autonómico" con el `system` de Andalucía. Así el perfil vale para otra comunidad sin rehacerlo.
2. **Sin `pattern` ni regex** en los identificadores que el laboratorio no emite: `system` + `value`
   como **cadena opaca**, `0..1`, `Must Support`. **Solo el NHC propio** lleva `1..1` y validación de
   formato. **El NUHSA nunca es `1..1`.**
3. **URIs canónicas propias** bajo `https://aojeda006.github.io/HispaLIS/fhir`, con los `system` de
   identificador colgando de `{base}/sid/…`. Se publican en la IG y **queda documentado que son
   propias, no oficiales**.
4. **Antes de fijarlas, consultar la IG española de ÚNICAS** (`unicas-fhir.sanidad.gob.es`): si
   define URI canónica para DNI o CIP-SNS, **se adopta la suya en vez de inventar**.

## Consecuencias

**Positivas**

- Un identificador ajeno que cambie de formato por Real Decreto **no** convierte el proyecto en un
  despliegue urgente. Ya se ha modificado tres veces (2004 → 2013 → 2024).
- Se evitan **falsos rechazos de pacientes reales**, que es lo único que puede producir validar el
  formato de un código que no emites.
- El perfil no queda sobre-restringido, y `required` sobre conjuntos que en la práctica no están
  cerrados es un anti-patrón declarado en la convención propia.

**Negativas, asumidas**

- El sistema acepta valores sintácticamente inválidos en los identificadores ajenos. Es el
  compromiso correcto: la validación real de esos códigos solo la puede hacer su emisor.
- Las URIs propias **son deuda de interoperabilidad** si mañana aparece un juego oficial. Se mitiga
  publicándolas y documentándolas explícitamente como propias, y consultando ÚNICAS antes.
- La consulta a ÚNICAS es el **único punto del proyecto con riesgo real de retrabajo**, y hay que
  hacerla **antes** de escribir el FSH de `PacienteLabES` (ítem 0 del checklist).

## Alternativas consideradas

- **Fijar el formato del CIP-SNS y el NUHSA en el perfil** — descartado por los tres motivos de
  arriba. Además, la estructura interna del CIP-SNS (Anexo I del RD 183/2004) no está contrastada
  contra fuente primaria: fijarla sería codificar una suposición.
- **Tratar NUHSA y CIP-SNS como identificadores paralelos** — descartado: es factualmente incorrecto y
  produce un perfil que solo sirve para Andalucía.
- **`urn:oid:` para los `system`** — descartado: opaco, sin resolución y sin autoridad española que
  los asigne para este caso.
- **Un dominio propio para las canónicas** — descartado: la IG ya se publica en GitHub Pages, así que
  la URI canónica **resuelve** al artefacto publicado sin infraestructura adicional.
