# `simuladores/` — generador de datos sintéticos, HIS y analizador

| Carpeta | Qué es | Hito |
|---|---|---|
| `generador/` | Generador de **datos sintéticos** españoles: pacientes, peticiones, especímenes, resultados e informes | **1** |
| `his/` | Simulador del **HIS** de la clínica: emitirá `ADT^A01`/`A08` y `OML^O21` por MLLP | 2 |
| `analizador/` | Simulador del **analizador**: emitirá `ORU^R01` por MLLP | 2 |

> **Nunca datos reales de pacientes.** Todo lo que sale de aquí es sintético, en cualquier entorno.

## Puesta en marcha

```bash
cd simuladores
python -m venv .venv && source .venv/bin/activate   # en Windows: .venv\Scripts\activate
pip install -e ".[dev]"
```

**Antes de nada, compila la terminología de la guía.** El generador la lee, no la copia:

```bash
cd ../ig && npx --yes fsh-sushi . && cd ../simuladores
```

Sin ese paso el generador **no arranca** y te dice exactamente qué ejecutar. Es a propósito:
arrancar con un catálogo a medias produce un corpus que parece bueno y no lo es.

## Comandos

```bash
pytest                                  # tests
ruff check . && ruff format --check .   # lint y formato
python -m generador --seed 42 --pacientes 100
python -m generador --seed 42 --pacientes 40 --fecha 2026-08-05 --salida corpus
```

La salida son dos cosas: `corpus/recursos/` con un fichero JSON por recurso FHIR y nada más —el
validador oficial recorre ese directorio y falla con cualquier JSON que no sea un recurso—, y
`corpus/manifiesto.json` con qué se generó y qué salió.

## Lo que hace útil al corpus

- **La terminología sale de la guía** (D15): el mismo `CodeSystem` de pruebas y el mismo
  `ConceptMap` a LOINC que usa el backend. Una lista paralela produciría datos que solo valen para
  sí mismos, y dejaría el `ConceptMap` sin probar por nadie.
- **Apellidos que rompen sistemas:** `MUÑOZ`, `ÁLVAREZ` y `PEÑA` prueban el charset de punta a
  punta; «de la Torre Gómez» y «Fernández de Córdoba Ruiz» prueban que **no** se parte por el
  espacio. Están garantizados en toda ejecución, porque de un generador aleatorio no se obtiene una
  garantía sino una probabilidad.
- **DNI y NIE con su letra correcta**, incluidas las iniciales `Y` y `Z`, cuyo control se calcula
  distinto y es donde falla quien no lo mira.
- **Una parte de los pacientes sin NUHSA ni CIP-SNS**: es el caso real de un laboratorio privado, y
  un corpus donde todos lo traen deja sin probar el que más se da en la puerta.
- **Resultados que se comportan como resultados:** se piden por paneles, el hematocrito cuadra con
  la hemoglobina, el rango de referencia depende del sexo en la serie roja, y una TSH alta dispara
  una T4 libre enlazada con `Observation.triggeredBy` (nuevo en R5).
- **Muestras rechazadas sin resultados**, para que el corpus ejercite el invariante C6 del
  laboratorio y no solo el camino feliz.

## Reproducibilidad

El generador hace **doble función**: juego de datos de prueba y arnés de carga. Sin reproducibilidad
no sirve para lo segundo, porque no se puede comparar nada.

La salida queda fijada por **tres** parámetros, no por uno: `--seed`, `--pacientes` y `--fecha`. La
fecha entra en el trato porque la actividad se reparte hacia atrás desde ese día, así que sin fijarla
dos ejecuciones en días distintos difieren. Hay un test que compara dos volcados completos, y otro
—el control negativo— que comprueba que con otra semilla la salida cambia: sin él, un generador que
devolviera siempre lo mismo aprobaría el primero con matrícula de honor.

La versión de Faker está acotada en `pyproject.toml` por la misma razón: sus corpus cambian entre
versiones mayores y la salida cambiaría con ellos.
