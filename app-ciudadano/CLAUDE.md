# CLAUDE.md — `app-ciudadano/` (Flutter)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado. **Componente del hito 2**: no se
> toca hasta cerrar el hito 1.

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/stacks/dart/convenciones.md
@../../BibliotecaDocumentacion/stacks/flutter/convenciones.md
@../../BibliotecaDocumentacion/bases-de-datos/almacenamiento-local/convenciones.md
@../../BibliotecaDocumentacion/patrones/mvvm.md
@../../BibliotecaDocumentacion/ux-ipo/convenciones.md
@../../BibliotecaDocumentacion/interoperabilidad/smart-on-fhir/convenciones.md

---

## Qué construye

La app del **paciente** para consultar sus resultados. **Flutter** (D13) porque el objetivo declarado
son clientes multiplataforma y en España iOS es aproximadamente la mitad del mercado: una app de
resultados solo para Android no cumple la premisa.

Autorización: **SMART standalone launch + PKCE**, cliente **público**, *scopes* `patient/*.rs`.

## Reglas

- **Cliente público: nunca un `client_secret` en la app.** PKCE obligatorio. Los *tokens* van al
  almacén seguro de la plataforma, **nunca** a `SharedPreferences` ni a un fichero en claro.
- **Un *scope* concedido no garantiza los datos:** el consentimiento se aplica en el servidor FHIR.
  No asumas que `patient/*.rs` devuelve todo; maneja el `403`/`OperationOutcome` con un mensaje
  comprensible en español.
- **Habla FHIR R5 y solo R5.**
- **Datos clínicos en caché local: el mínimo imprescindible y cifrado.** Nada de PHI en logs, trazas
  ni analítica. Al cerrar sesión, se borra.
- **Apellidos:** muestra `HumanName.family` completo; **nunca lo partas por el espacio**.
- **Casos de prueba obligatorios de charset:** `MUÑOZ`, `ÁLVAREZ`, `PEÑA`.
- **Un resultado sin contexto asusta:** muestra siempre unidad y rango de referencia junto al valor, y
  di explícitamente cuándo un informe aún no está validado por el facultativo.
- **Todo el texto de usuario en español.**

## Comandos

```bash
cd app-ciudadano
flutter pub get
flutter analyze
flutter test
flutter run
```
