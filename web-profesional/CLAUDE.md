# CLAUDE.md — `web-profesional/` (Angular)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado (principios, `interoperabilidad/fhir`
> y `seguridad` ya vienen de allí: **no** se repiten aquí).

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/stacks/typescript/convenciones.md
@../../BibliotecaDocumentacion/stacks/angular/convenciones.md
@../../BibliotecaDocumentacion/patrones/mvvm.md
@../../BibliotecaDocumentacion/ux-ipo/convenciones.md

---

## Qué construye

La web del **profesional del laboratorio**: alta de petición y consulta de informe, **contra la API
FHIR real** (nada de *mocks* en el criterio de aceptación del hito 1), con lanzamiento **SMART EHR
launch** (ítem 37).

## Reglas

- **Habla FHIR R5 y solo R5.** Los tipos del cliente se generan o se escriben contra R5; ojo con
  `ServiceRequest.code`, que es **`CodeableReference`**, no `CodeableConcept` (ver §2.1 del diseño).
- **La paginación se sigue por `Bundle.link[relation=next]`**, nunca construyendo la URL a mano.
- **Los errores llegan en `OperationOutcome`**: preséntalos al usuario en español, sin volcar el
  recurso crudo ni el *stack trace*.
- **Nunca PHI en la URL** (ni en query params, ni en el fragmento), ni en logs de navegador, ni en
  analítica.
- **Los `display` se muestran en español** — vienen así del servidor de terminología; no los traduzcas
  en el cliente ni los inventes: si falta, pide `$lookup`.
- **Apellidos:** muestra `HumanName.family` completo. **Nunca lo partas por el espacio** para separar
  primer y segundo apellido; si necesitas la descomposición, usa las extensiones
  `humanname-fathers-family` / `humanname-mothers-family`.
- **Casos de prueba obligatorios de charset:** `MUÑOZ`, `ÁLVAREZ`, `PEÑA`.
- **Sin secretos en el bundle.** El cliente es **público, con PKCE `S256`** y no lleva
  `client_secret`: todo lo que viaja en el paquete que se descarga el navegador lo puede leer
  cualquiera, así que un secreto ahí no es un secreto.
- **Accesibilidad y claridad no son opcionales:** es una herramienta de trabajo clínico, donde
  confundir un paciente o una unidad tiene consecuencias. Unidad **siempre** junto al valor, y el
  rango de referencia visible junto al resultado.

## El lanzamiento SMART (`src/app/seguridad/`)

`/launch` descubre, prepara el PKCE y redirige; `/callback` canjea el código y abre la sesión. Cinco
cosas que no son opcionales:

- **Nada se cablea.** De la base FHIR sale `.well-known/smart-configuration`, y de ahí el
  `authorization_endpoint` y el `token_endpoint`.
- **El `iss` se comprueba contra una lista.** Llega por la URL y decide a dónde se manda al usuario a
  identificarse: aceptar cualquiera es **la** vulnerabilidad clásica del EHR launch.
- **`state` de 256 bits y PKCE `S256`**, los dos con `crypto.getRandomValues`. Si el servidor no
  ofrece `S256`, no se lanza: caer a `plain` es mandar el verificador en claro.
- **`user/*.rs` no basta para el alta.** `.rs` es solo lectura; la pantalla de alta **crea** recursos.
  Se piden además `user/Patient.c`, `user/Practitioner.c` y `user/ServiceRequest.c` — y ni uno más:
  `user/*.cruds` daría de paso permiso para borrar informes.
- **El testigo va en un interceptor y solo a las llamadas del laboratorio** (relativas y absolutas
  del mismo origen, que es como vuelve `Bundle.link[relation=next]`). Un testigo enviado a quien no
  le corresponde es un testigo entregado.

La sesión vive en `sessionStorage`, no en una cookie: la cookie la manda el navegador sola en cada
petición al origen —eso es CSRF— y aquí el testigo lo pone la aplicación a mano. Con XSS el testigo
es legible y una cookie `httpOnly` tampoco lo salvaría; la respuesta es que no haya XSS.

La guarda de ruta **no es control de acceso**: eso se aplica en el laboratorio. Solo evita una
pantalla que iba a contestar `401` en cuanto pidiera algo.

## Decisiones ya tomadas — no las deshagas por costumbre

- **Se busca con `POST [tipo]/_search`, no con `GET [tipo]?…`.** Los criterios llevan el número de
  historia, y una URL con eso dentro se queda en la barra del navegador, en su historial, en el log
  del proxy y en la traza del servidor. FHIR admite los mismos criterios en el cuerpo, y hay un test
  del backend (`BusquedaSinPhiEnLaUrlTest`) que comprueba que el servidor lo acepta.
- **El catálogo de pruebas no se escribe aquí.** Lo trae `scripts/traer-terminologia.mjs` de
  `ig/fsh-generated/` (D15) a `public/terminologia/`, que está en `.gitignore`. Si falta, el build
  para y dice que ejecutes `npx fsh-sushi .`. Una lista de códigos en TypeScript sería una cuarta
  versión de la verdad. En el hito 2, cuando exista el servidor de terminología, esto pasa a `$expand`.
- **`proxy.conf.json` lleva `"xfwd": true`, y no es decorativo.** El servidor firma
  `Bundle.link[relation=next]` con la dirección por la que le llegó la petición; sin las cabeceras
  `X-Forwarded-*` la página siguiente apuntaría a `localhost:8080` y el navegador no la alcanzaría.
  El cliente **no puede corregir esa URL** porque para él es opaca, así que el fallo solo aparecería
  al pasar de la primera página.
- **Los `system` de identificador viven en `src/app/fhir/sistemas.ts`** y un test los cruza contra
  `ig/input/fsh/aliases.fsh`. Añadir uno nuevo obliga a añadirlo también al test.

## Comandos

```bash
cd web-profesional
npm ci
npm start          # servidor de desarrollo (proxy /fhir → localhost:8080)
npm test           # tests
npm run lint
npm run build      # trae antes la terminología de la guía
```
