Esta página describe **lo que el laboratorio hace y lo que un cliente puede esperar** en cada
momento del circuito. Los perfiles dicen qué forma tiene cada recurso; esto dice qué significa que
un recurso esté en un estado y no en otro, que es lo que un integrador no puede deducir leyendo un
`StructureDefinition`.

---

### El circuito

```
petición ──► extracción ──► espécimen ──► medición ──► validación facultativa ──► informe ──► entrega
```

| Paso | Recurso | Estado al terminar |
|---|---|---|
| Se pide una prueba | [PeticionLab](StructureDefinition-peticion-lab.html) | `active` |
| Llega la muestra | [EspecimenLab](StructureDefinition-especimen-lab.html) | `available`, o `unsatisfactory` si se rechaza |
| El analizador mide | [ResultadoLab](StructureDefinition-resultado-lab.html) | **`preliminary`** |
| Un facultativo firma | `ResultadoLab` + [ProcedenciaValidacion](StructureDefinition-procedencia-validacion.html) | **`final`** |
| Se emite el informe | [InformeLab](StructureDefinition-informe-lab.html) | `final` |

---

### La validación facultativa: qué separa `preliminary` de `final`

**Es la frontera de la responsabilidad, no un detalle de flujo de trabajo.** En este laboratorio:

- **`preliminary`** — la cifra existe y es técnicamente correcta: la ha dado el analizador, tiene su
  unidad y su rango de referencia. **Nadie responde de ella todavía.** Puede cambiar, puede
  retirarse, y no es una respuesta a lo que el peticionario pidió.
- **`final`** — un facultativo la ha revisado y la firma. A partir de aquí el laboratorio responde
  del valor, y existe un [ProcedenciaValidacion](StructureDefinition-procedencia-validacion.html)
  que dice **quién** y **cuándo**.

#### Un valor crítico necesita dos firmas, y de personas distintas

Cuando la cifra alcanza el umbral que publica el catálogo
([ValoresCriticos](ValueSet-valores-criticos.html)), una sola revisión no basta: el resultado se
queda en **`preliminary` con una firma puesta** hasta que lo valide un **segundo facultativo
distinto**. Cada firma deja su propia `ProcedenciaValidacion`, porque cada una es un acto de una
persona concreta en un momento concreto.

Un potasio de 6,9 mmol/L no es «un valor alto»: es una cifra por la que se llama por teléfono antes
de que el informe salga, y la respuesta clásica del oficio es que **lo mire otra persona**. Que sea
otra es la mitad que importa — la misma mirando dos veces no es una revisión independiente.

Para un cliente, esto significa que **un `preliminary` puede tener ya un `Provenance`**. Buscar
procedencias no responde «¿está validado?»; eso lo dice `Observation.status`, y solo eso.

Tres consecuencias más que un cliente tiene que implementar:

1. **Un `InformeLab` solo contiene resultados `final`.** No hay informes «provisionales» con
   determinaciones sin firmar dentro. Si una línea del volante todavía no tiene resultado validado,
   el informe **no se emite**: se espera, o se anula la línea.
2. **Quien enseñe un `preliminary` tiene que decir que lo es**, con todas las letras y a la vista.
   Presentar una cifra sin firmar con el mismo aspecto que una firmada es la forma más eficaz de que
   alguien tome una decisión clínica sobre un dato del que nadie responde. La aplicación del
   ciudadano de HispaLIS lo dice **por encima del primer valor**, no en una nota al pie.
3. **`Observation.status` no basta para saber quién firmó.** Eso está en el `Provenance`, y se
   consulta con `POST Provenance/_search` y `target=Observation/{id}`.

**Revalidar está prohibido.** Un resultado se valida las veces que su regla exige —una, o dos si es
crítico— y ni una más; corregirlo es emitir una corrección (`status = corrected`), no volver a firmar
el mismo. Una firma de más taparía a las anteriores y reescribiría sin dejar constancia el rastro de
quién respondió del valor.

Por eso el perfil declara `target 1..1`: **cada procedencia da fe de un acto y apunta a un solo
resultado**. Lo que no es uno a uno es la relación inversa — un crítico tiene dos procedencias—, y
fundirlas en una con dos agentes diría que las dos personas firmaron a la vez lo mismo, cuando lo que
pasó fue una revisión y después una contra-revisión.

**La procedencia no se puede escribir desde fuera.** `POST` y `PUT` contra `Provenance` se rechazan:
un cliente que pudiera crearla estaría certificando una validación que aquí no ha ocurrido. Para
dejar constancia de una, hay que validar el resultado — y eso lo hace el laboratorio.

---

### La anulación de una línea

Una línea de petición que ya no procede se cierra con **`status = revoked`** y **el motivo en
`note`**. Ejemplo: [peticion-anulada](ServiceRequest-peticion-anulada.html).

**Por qué en `note` y no en `statusReason`:** ⚠️ **R5 no da `statusReason` en `ServiceRequest`.** Lo
tienen `MedicationRequest`, `Task` y `CarePlan`; este recurso no. Verificado contra
`hl7.fhir.r5.core@5.0.0`.

De las tres salidas posibles, `note` es la única honesta. Una extensión propia se descartó porque
hay elemento estándar donde escribirlo. `ServiceRequest.reason` se descartó porque dice por qué se
**pidió** la prueba: escribir ahí el motivo de la anulación corrompe un dato clínico. Queda `note`,
con **una pérdida real que conviene decir en voz alta**: es texto libre, así que un sistema que
quiera contar anulaciones por causa no puede hacerlo con esto.

**Una línea anulada no vuelve.** No se reactiva, y no admite un espécimen nuevo. Repetir la prueba
es registrar otra línea.

---

### La ventana de huérfano: un `ServiceRequest` sin `Specimen`

**Un `ServiceRequest` sin su `Specimen` es un estado transitorio legítimo de este sistema.** No es
un error de datos y no hay que tratarlo como tal.

**Por qué ocurre.** Un mensaje `OML^O21` que entra por el motor de integración produce **dos**
recursos, un `ServiceRequest` y un `Specimen`, que querrían escribirse juntos. Este servidor **no
admite `transaction`** (ver [Cómo se usa la API](uso-de-la-api.html)), así que el motor los escribe
de uno en uno. Si falla entre el primero y el segundo, el volante queda publicado sin su muestra.

**Quién lo cierra.** El motor guarda el mensaje original íntegro antes de tocarlo; el fallo manda el
mensaje a una bandeja de errores, y **el reproceso vuelve a aplicarlo entero**. El reproceso es
idempotente: reaplicar el mismo mensaje no duplica el volante. La atomicidad no está en la escritura
sino en el mensaje guardado y su reproceso.

**Qué debe hacer un cliente:**

- **No** inferir que la petición es inválida, ni borrarla, ni marcarla en rojo.
- **No** dar por perdida la muestra: lo más probable es que llegue en cuestión de segundos.
- Si el estado importa para una decisión, **volver a leer**. La ventana se cierra sola.

Lo que **no** puede ocurrir, y por eso no hay que defenderse de ello: que salga un resultado de un
espécimen que no existe, o de uno rechazado. Eso lo impide el núcleo, no el orden de escritura.
