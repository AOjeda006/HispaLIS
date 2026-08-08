# El realm `hispalis` — configuración como código

`hispalis-realm.json` es **la** definición del servidor de identidad de HispaLIS. Entra por
`--import-realm` al arrancar el contenedor y se versiona como cualquier otro fuente.

**Nada de esto se toca en la consola de administración.** Un realm clicado a mano no se revisa, no se
despliega y no se puede reconstruir después de un `docker compose down -v`. Si hace falta cambiar
algo, se cambia aquí y se levanta la pila de nuevo.

```bash
cp infra/compose/.env.example infra/compose/.env    # y pon las contraseñas
docker compose -f infra/compose/docker-compose.yml up keycloak keycloak-usuarios
# consola: http://localhost:8081  ·  realm: hispalis
```

## Lo que hay dentro

### Clientes

| Cliente | Tipo | Cómo se autentica | Para qué |
|---|---|---|---|
| `hispalis-web` | **público** | PKCE `S256`, sin secreto | La web del profesional (EHR launch y autónomo) |
| `hispalis-app-ciudadano` | **público** | PKCE `S256`, sin secreto | La app del ciudadano (lanzamiento autónomo con `launch/patient`) |
| `hispalis-motor` | confidencial | `private_key_jwt` **RS384**, JWKS **por URL** | El motor de integración (SMART Backend Services, D5) |

Los dos públicos no llevan `client_secret` y no pueden llevarlo: todo lo que viaja en el paquete que
se descarga el navegador lo puede leer cualquiera. Lo que los protege es PKCE.

El motor tampoco tiene secreto compartido, y por una razón mejor: **Keycloak solo conoce su clave
pública**, y se la baja de `http://motor:8082/motor/jwks.json`. Pegar la clave dentro del cliente
funcionaría y haría imposible rotarla sin una ventana de indisponibilidad; con la URL, Keycloak
relee el JWKS cuando ve un `kid` que no conoce y la rotación se solapa sola.

> ⚠️ Esa URL se resuelve **dentro de la red del `compose`**. El motor se arranca aparte (ítem 41):
> mientras no esté en la misma red, Keycloak no podrá bajarse su JWKS y el canje devolverá
> `invalid_client`. Con el motor en local, cámbiala por `http://host.docker.internal:8082/...`.

### Scopes

Los de permiso son literalmente los de SMART v2, con la barra y el asterisco dentro del nombre
(`user/*.rs`, `system/Patient.crus`). Keycloak los admite sin problema.

- **`hispalis-web`**: `openid`, `fhirUser`, `launch`, `user/*.rs` y los tres `.c` que necesita el
  alta —`user/Patient.c`, `user/Practitioner.c`, `user/ServiceRequest.c`—. Con `user/*.rs` a secas la
  pantalla de alta contestaría `403` en el momento de guardar: `.rs` es solo lectura.
- **`hispalis-app-ciudadano`**: `openid`, `fhirUser`, `launch/patient`, `patient/*.rs`. **Solo
  lectura**, y solo del paciente sobre el que se lanzó — el laboratorio lo aplica recurso a recurso.
- **`hispalis-motor`**: los cinco `system/` que escriben los canales, y ni uno más.

**`system/*.cruds` está definido y no está asignado a ningún cliente.** Es lo que exige
`POST /fhir/$reconciliar`, que **borra** recursos publicados de cualquier tipo. Dárselo a alguien
tiene que ser un acto explícito de alguien, no una consecuencia de una plantilla.

### Usuarios de demostración

`dra.alvarez` (rol `facultativo`, `fhirUser: Practitioner/dra-alvarez`), `paciente.demo` y
`paciente.otro`. **Sin contraseñas en el fichero**: las pone el servicio `keycloak-usuarios` del
`compose` desde `HISPALIS_KEYCLOAK_CLAVE_DEMO`. Un realm exportado con credenciales dentro es un
secreto commiteado, aunque sean de mentira: el día que alguien copie la plantilla para un entorno de
verdad, la contraseña se copia con ella.

## Cuatro trampas de Keycloak 26.4, medidas en vivo

Las cuatro se descubrieron levantando un Keycloak de usar y tirar y recorriendo los flujos con
`curl`. Ninguna aparece leyendo la documentación.

1. **Los `attributes` de usuario se descartan en silencio.** Desde Keycloak 24 los atributos no
   declarados están deshabilitados, así que `fhirUser` se importaba y el testigo lo devolvía vacío
   —sin error, sin aviso en el log—. Se arregla declarando el perfil de usuario en
   `components["org.keycloak.userprofile.UserProfileProvider"]`. Es mejor que habilitar los atributos
   sin declarar: así el fichero **documenta** qué atributos existen.
2. **Un perfil de usuario declarado tiene que conservar los cuatro atributos base.** Si falta
   `email`, la importación aborta con `[The attribute 'email' can not be removed]`.
3. **`description` de un cliente son 255 caracteres.** Uno más y la importación muere con
   `Value too long for column "DESCRIPTION CHARACTER VARYING(255)"`. Las explicaciones largas van a
   la documentación, que es donde se leen.
4. **`access.tokenResponse.claim` no lo honra el `oidc-usermodel-attribute-mapper`.** La opción se
   guarda —se comprueba consultando la API de administración— y no tiene efecto. Con
   `oidc-hardcoded-claim-mapper` sí funciona, así que no es un fallo de configuración. Consecuencia
   práctica: **el `patient` del contexto viaja como reclamación del testigo de acceso**, que además
   es lo correcto —es lo que el laboratorio comprueba, y fiarse de lo que diga la aplicación sería
   fiarse de la parte que se puede manipular—. Una app de ciudadano averigua su sujeto por el
   `fhirUser` del `id_token`.

## Cambiar el realm

1. Se edita `hispalis-realm.json`.
2. `docker compose down -v keycloak` y `up` — la importación **no** actualiza un realm que ya existe.
3. Se comprueba el flujo afectado de punta a punta. Un realm que importa sin quejarse puede tener un
   mapeador que no hace nada: la trampa 1 y la 4 pasaron exactamente eso.
