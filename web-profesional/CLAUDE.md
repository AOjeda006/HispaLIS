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
FHIR real** (nada de *mocks* en el criterio de aceptación del hito 1). En el hito 2 se le añade
lanzamiento **SMART EHR launch** con *scopes* `user/*.rs`.

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
- **Sin secretos en el bundle.** En el hito 1 no hay Keycloak; cuando llegue (hito 2), el cliente es
  público con **PKCE** y no lleva `client_secret`.
- **Accesibilidad y claridad no son opcionales:** es una herramienta de trabajo clínico, donde
  confundir un paciente o una unidad tiene consecuencias. Unidad **siempre** junto al valor, y el
  rango de referencia visible junto al resultado.

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
