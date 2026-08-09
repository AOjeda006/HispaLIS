Lo que un integrador necesita saber **antes de escribir el primer cliente**, y que no está en
ningún `StructureDefinition`: cómo se autoriza, qué verbos hay y cuáles no, y dónde este servidor se
aparta de lo que un cliente FHIR genérico da por hecho.

El documento de referencia en tiempo de ejecución es `GET [base]/metadata`. Esta página explica el
**porqué** de lo que allí se declara.

---

### Cómo se autoriza

La API exige testigo. Sin él, cualquier petición contesta **`401`** con un `OperationOutcome`; nunca
un `200` con un error dentro.

El servidor se autoriza con **SMART on FHIR**. Un cliente descubre dónde autorizarse partiendo solo
de la URL base:

```
GET [base]/.well-known/smart-configuration
```

**PKCE con `S256` es obligatorio**, y es lo único que se admite: no hay `plain`, y las aplicaciones
públicas —la web y la del ciudadano— **no tienen `client_secret`**. Una aplicación móvil o de
navegador no puede guardar un secreto, y fingir que sí es peor que no tener ninguno.

#### Los *scopes* que este laboratorio concede

| Quién | *Scopes* | Qué puede hacer |
|---|---|---|
| **Profesional** (web del laboratorio) | `openid` `profile` `fhirUser` `user/*.rs` `user/Patient.c` `user/Practitioner.c` `user/ServiceRequest.c` | Leer y buscar todo el laboratorio; dar de alta pacientes, facultativos y líneas de petición |
| **Ciudadano** (app del paciente) | `openid` `profile` `fhirUser` `launch/patient` `patient/*.rs` | Leer y buscar **lo suyo, y solo lo suyo** |
| **Sistema** (motor de integración) | `system/Patient.crus` `system/ServiceRequest.cs` `system/Specimen.cs` `system/Observation.crs` `system/DiagnosticReport.r` | Escribir lo que traduce de HL7 v2 |

Cuatro cosas que se descubren tarde si no se dicen:

1. **El comodín de `patient/*.rs` es sobre el tipo de recurso, no sobre la persona.** Quién es el
   paciente lo fija el testigo, no la petición. Pedir la historia de otra persona con un testigo de
   ciudadano devuelve **`403`**, y la respuesta **no contiene ni el nombre** de esa persona.
2. **Un sufijo se lee entero o no se lee.** `.rs` es leer y buscar; `.c` es **crear**, que es `POST`
   — no incluye actualizar. Un `PUT` con un id elegido por el cliente contra un recurso para el que
   solo se tiene `.c` devuelve `403`, y eso es correcto. Un sufijo desordenado, repetido o inventado
   **no concede nada**: no se «corrige» `.dus` a `.cud`, porque eso le daría a quien pidió
   actualizar el permiso de borrar.
3. **Hay dos formas de decir que no, y la diferencia es deliberada.** Una lectura directa de un
   recurso ajeno devuelve `403`. Una **búsqueda** omite el recurso **en silencio**: contestar «hay
   tres que no te enseño» ya cuenta algo de quien no lo autorizó.
4. **Un *scope* concedido no garantiza el dato.** El consentimiento se aplica en el servidor de
   recursos, no en el de identidad ni en un proxy.

#### El motor de integración se identifica sin secreto

El motor usa **SMART Backend Services**: no hay usuario, no hay navegador y no hay secreto
compartido. Firma con su clave privada una aserción y la canjea por un testigo `system/`. La
aserción dura cinco minutos como mucho, su `jti` es nuevo cada vez, y su `aud` es el
`token_endpoint` —no la URL del laboratorio—. No hay testigo de refresco: cuando caduca se firma
otra.

---

### Lo que este servidor **no** hace

#### No admite `Bundle` de tipo `transaction`

`GET [base]/metadata` **no declara `transaction`**, y no es un olvido: un `Bundle transaction` que
toque un recurso con reglas de negocio detrás se rechaza con **`422`**.

**Por qué.** El procesador de transacciones escribe llamando a la capa de persistencia directamente
y **no pasa por el núcleo de dominio**, que es donde viven los invariantes del laboratorio: que una
muestra rechazada no produce resultados, que un informe no se emite con líneas pendientes, que un
número de historia es válido. Una transacción se saltaría todos. Medido, antes de cerrarla: un
`POST [base]` con un `Patient` dentro devolvía `201 Created` con un id numérico, sin número de
historia validado y **sin paciente** detrás del recurso publicado.

Entre publicar recursos que no existen y no ofrecer un verbo estándar, se elige lo segundo, y se
dice con todas las letras en vez de dejar que se descubra fallando.

**Qué hacer en su lugar:**

- **Enviar los recursos uno a uno**, en orden de dependencia: primero el `Patient`, después el
  `ServiceRequest` que lo referencia, después el `Specimen`.
- **Asumir que puede fallar por la mitad**, y tener con qué reintentar. La forma que usa el propio
  motor de integración es la recomendada: **guardar la petición original íntegra** y reaplicarla
  entera si algo falla, con **reproceso idempotente** —buscar antes de crear, para que reaplicar no
  duplique—.
- **Contar con la [ventana de huérfano](flujo-y-estados.html)**: entre el primer recurso y el
  segundo hay un instante en que el primero está publicado y el segundo no. Es un estado transitorio
  legítimo.

Una transacción de **solo datos maestros** (`Organization`, `Practitioner`) sí se admite: no tienen
agregado detrás y no se saltan nada.

#### No es un servidor de terminología

El laboratorio **no** publica `$expand`, `$validate-code`, `$lookup` ni `$translate`: los pregunta a
un servidor de terminología aparte. Un cliente que necesite expandir el catálogo de pruebas debe ir
a ese servidor, no a este.

---

### Dos cosas que sí conviene usar

#### Buscar con `POST`, no con `GET`

Los criterios de búsqueda sensibles van **en el cuerpo**, con
`POST [base]/[tipo]/_search` y `application/x-www-form-urlencoded`:

```http
POST [base]/Patient/_search
Content-Type: application/x-www-form-urlencoded

identifier=https%3A%2F%2Faojeda006.github.io%2FHispaLIS%2Fsid%2Fnhc%7C70000001
```

Es la forma estándar de FHIR y aquí es la **preferente**, no una alternativa: un número de historia
o un CIP en la barra de direcciones acaba en el registro del proxy, en el historial del navegador y
en el `Referer`. Ninguno de esos tres sitios se limpia.

#### La paginación la firma el servidor

Nunca construyas la URL de la página siguiente a mano: usa `Bundle.link[relation=next]` tal cual, y
trátala como opaca.

---

### Errores

Todo error llega como `OperationOutcome` con el código HTTP que le corresponde. Los que más se
encuentran:

| Código | Qué significa aquí |
|---|---|
| `401` | Sin testigo, o con uno caducado, mal firmado o emitido para otro servidor |
| `403` | El testigo es válido pero no autoriza **esto**: falta el *scope*, o el recurso es de otra persona |
| `412` | `PUT` con un `If-Match` de una versión que ya no es la actual |
| `422` | El recurso está bien formado y **el laboratorio no lo acepta**: una prueba fuera del catálogo, un resultado de una muestra rechazada, un `Bundle transaction` |

`422` y `400` no son intercambiables: `400` es un recurso mal formado, `422` es una regla de
negocio. Un cliente que los confunda reintentará lo que nunca va a entrar.
