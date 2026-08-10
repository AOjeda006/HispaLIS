# CLAUDE.md — `simuladores/` (Python: generador de datos, HIS y analizador)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado.

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/stacks/python/convenciones.md

---

## Qué hay aquí

| Carpeta | Qué es | Hito |
|---|---|---|
| `generador/` | Generador de **datos sintéticos** (D15) — pacientes, peticiones, especímenes y resultados | **1** |
| `his/` | Simulador del **HIS de la clínica**: emite `ADT^A01`/`A08` y `OML^O21` por MLLP | 2 |
| `analizador/` | Simulador del **analizador**: emite `ORU^R01` por MLLP | 2 |
| `receptor/` | Receptor de **notificaciones de `Subscription`**: comprueba la firma, exige `id-only` y detecta los eventos que faltan | 3 |
| `svea/` | Servicio de declaraciones del **SVEA** (Salud Pública): exige que la declaración no lleve filiación, que traiga plazo, deduplica y acusa | 3 |

## Reglas del generador (las que lo hacen útil o inútil)

- **Resuelve la terminología contra el mismo servidor que el backend y el motor** (D14, D15), nunca
  una lista paralela ni un fichero propio. Si se desvía, genera datos que solo valen para sí mismo —
  y el `ConceptMap` deja de estar probado. `$expand` da el catálogo, `$lookup` el nombre en español y
  la unidad UCUM, `$translate` el LOINC, `$validate-code` los tipos de muestra. La URL sale de
  `HISPALIS_TERMINOLOGIA` o de `--terminologia`; los tests levantan el suyo en `tests/conftest.py`,
  cargado con lo que produce SUSHI.
- **Sin servidor no se genera.** Un corpus con un catálogo a medias es peor que ninguno: nadie se
  entera hasta mirarlo.
- **Lo difícil no es la demografía, son los resultados clínicamente verosímiles:** paneles
  correlacionados (un hemograma cuyos campos cuadren entre sí), valores dentro y fuera de rango, y
  disparos de reflejas que ejerciten `Observation.triggeredBy` (TSH alterado → T4 libre).
- **Localización española real** (`Faker` con locale `es_ES`):
  - **Apellidos dobles** — `HumanName.family` con el nombre familiar **completo**; casos como
    `"de la Torre Gómez"` y `"Fernández de Córdoba Ruiz"` deben aparecer en el corpus, porque son los
    que rompen el heurístico de partir por el espacio.
  - **DNI/NIE con dígito de control válido.**
  - **NUHSA con formato correcto:** `AN` + 10 dígitos.
  - **Códigos INE** de provincia y municipio (Sevilla = provincia **41**).
  - **`MUÑOZ`, `ÁLVAREZ` y `PEÑA` entre los casos generados** — obligatorio, no opcional.
- **El NUHSA no es universal:** en un laboratorio privado, mutualistas y privados con frecuencia no lo
  conocen. Una parte de los pacientes generados **debe salir sin NUHSA** (y sin CIP-SNS), o el sistema
  nunca se prueba contra el caso real.
- **Doble función: arnés de carga y de pruebas.** Semilla (`seed`) parametrizable y salida
  reproducible; sin reproducibilidad no sirve como arnés.
- **Nunca datos reales de pacientes**, ni siquiera anonimizados a ojo. Solo sintéticos.

## Reglas del receptor de notificaciones (hito 3)

- **Sin clave compartida no arranca.** Aceptar una notificación sin firma es aceptarla de cualquiera.
- **Comprueba `id-only` desde el lado que recibe.** Una entrada con el recurso dentro se rechaza con
  `400`. No es redundancia con el backend: el contrato tiene dos lados y solo se demuestra desde los
  dos, y un receptor que se traga un recurso que no pidió acaba almacenando historia clínica sin
  saber de dónde salió.
- **Detecta los huecos de `eventNumber`.** Es para lo que el número existe: con entrega «al menos una
  vez» y un canal que puede caerse, no hay otra forma de enterarse de lo que NO llegó. Lo que falte
  se recupera con `$events`.
- **Contesta el código que corresponde.** El laboratorio lo apunta como motivo del fallo y acaba
  saliendo por su `$status`; un receptor que traga en silencio deja al emisor creyendo que va bien.

## Reglas del SVEA simulado (hito 3)

- **Verosímil, no fiel, y escrito en el propio módulo.** El contrato de Redalerta no es público, así
  que se acepta el `Task` que publica la guía; el número de registro se lo inventa este servicio. La
  diferencia importante: una declaración EDO **real lleva filiación** —Salud Pública tiene que poder
  localizar al caso para la encuesta epidemiológica— y **esta no**. No es un descuido, y por eso está
  dicho en el docstring del módulo, en el perfil `NotificacionEDO` y en `docs/PLAN.md`.
- **Exige que NO llegue filiación, y ese es el motivo de que esté aquí** y no como un doble dentro
  del backend. El invariante 6 tiene dos lados: la promesa del emisor solo es una garantía si el
  receptor la exige. Se rechaza con `400` un `contained`, las claves que en FHIR solo cuelgan de una
  persona (`name`, `birthDate`, `address`, `telecom`, `photo`, `gender`) y un `display` sobre una
  referencia a `Patient`/`Practitioner`/`RelatedPerson` — que es el nombre con otro nombre. Lo que
  **no** se puede comprobar así es el texto libre: por eso el laboratorio pone en `Task.note` motivos
  técnicos y nunca clínicos.
- **Deduplica por el id del `Task`.** El laboratorio reintenta hasta que hay acuse —tiene que
  hacerlo—, y un destinatario que no deduplica convierte cada reintento en un caso nuevo en la
  estadística epidemiológica. El mismo `Task` dos veces devuelve **el mismo** número de registro.
- **Sin plazo no se registra (`422`).** Una urgente sin priorizar es una ordinaria.
- **Lo que llega tarde se registra igual y se apunta.** El plazo no extingue la obligación: la hace
  tardía. Rechazar una declaración vencida dejaría el caso sin declarar, que es mucho peor.
- **Los cuatro modos son los cuatro finales que el notificador distingue**, no adornos:
  `acusa` · `rechaza` (`422`) · `sin-registro` (`200` sin número) · `silencio` (`503`). Sin poder
  provocarlos solo se ejercita el bueno, y el que más fácil se cuela por bueno es `sin-registro`.

## Reglas de los simuladores v2 (hito 2)

- **HL7 V2.5.1** (D12) y **charset declarado en `MSH-18`**. `MSH-10` único por mensaje: es la clave de
  deduplicación del motor, así que el simulador debe poder **repetirlo a propósito** para probar el
  camino de duplicados.

## Comandos

```bash
cd simuladores
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
pytest
ruff check . && ruff format --check .

# Generar necesita el servidor de terminología levantado y cargado (D14):
docker compose -f ../infra/compose/docker-compose.yml up -d terminologia terminologia-carga
python -m generador --seed 42 --pacientes 100

# El receptor de notificaciones va detrás de un perfil del compose: no es del laboratorio, es el
# sistema del hospital.
docker compose -f ../infra/compose/docker-compose.yml --profile notificaciones up -d receptor

# Salud Pública, igual, y apagada por defecto a propósito: con el SVEA parado el laboratorio valida
# los resultados igual y las declaraciones se quedan pendientes.
docker compose -f ../infra/compose/docker-compose.yml --profile edo up -d svea
curl http://localhost:8091/declaraciones   # el libro de registro, sin una sola referencia al caso

# Los caminos malos, uno por modo:
HISPALIS_SVEA_MODO=silencio docker compose -f ../infra/compose/docker-compose.yml --profile edo up -d svea
```
