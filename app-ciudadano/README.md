# `app-ciudadano/` — la app del paciente

Flutter. La persona abre la app desde el icono de su móvil, se identifica contra Keycloak con
**SMART standalone launch + PKCE** y consulta **sus** resultados de laboratorio.

## Cómo se arranca

```bash
cd app-ciudadano
flutter pub get
flutter analyze          # falla también con los avisos `info`
flutter test
```

Para verla funcionando hace falta la pila levantada (`docker compose … up -d` desde la raíz; ver el
`README.md` del repositorio). Después:

```bash
# Web: el puerto 8090 es el que está registrado como redirección en el realm.
flutter run -d chrome --web-port 8090

# Android (emulador): `localhost` es el emulador, el equipo es 10.0.2.2. La app ya lo resuelve sola.
flutter run -d emulator-5554
```

La base FHIR se puede cambiar al compilar:

```bash
flutter run --dart-define=HISPALIS_FHIR=https://laboratorio.example/fhir
```

Antes de entrar, la identidad tiene que estar **vinculada** a una historia del laboratorio; se hace
con `infra/keycloak/vincular-paciente.sh`, que está explicado en el `README.md` de la raíz.

## Cómo está montada

```
lib/
├── nucleo/          transversal: errores, almacén seguro, seguridad SMART, red, inyección
├── dominio/         Paciente, Informe, Resultado y el puerto del repositorio
├── datos/           el borde FHIR: mapeadores R5 → dominio, y el repositorio que lo implementa
└── presentacion/    MVVM: un `ChangeNotifier` por pantalla y widgets que solo miran
```

Las carpetas van **en español** como el resto del monorepo (invariante 9 de los nueve del proyecto,
en la memoria técnica §4.1).
No hay ficheros `.arb`: la app es monolingüe por diseño y `intl` entra solo por el formato de
números y fechas.

## Lo que no se negocia

- **No hay `client_secret`.** Una app que se descarga de una tienda no puede guardar un secreto. La
  seguridad del canje la pone PKCE `S256`.
- **Los testigos van al almacén cifrado de la plataforma** (Keystore / Keychain), nunca a
  `SharedPreferences` ni a un fichero.
- **No se guarda ni un dato clínico en el dispositivo.** Un resultado preliminar pasa a validado y
  una cifra puede corregirse: enseñar la copia de ayer como si fuera de ahora es un riesgo clínico.
  Al cerrar sesión se borra el almacén entero.
- **Ningún resultado sale sin unidad ni sin rango**, y cuando el laboratorio no publica rango, se
  dice con esas palabras en vez de dejar el hueco.
- **Una analítica sin validar lo dice arriba del todo**, antes de que se lea la primera cifra.
- **Los apellidos van enteros.** Nunca se parten por el espacio. `MUÑOZ`, `ÁLVAREZ` y `PEÑA` son
  casos de prueba obligatorios.
- **Nunca un `WebView` empotrado** para identificarse: navegador del sistema y sesión efímera.
