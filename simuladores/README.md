# `simuladores/` — generador de datos sintéticos, HIS y analizador

| Carpeta | Qué es | Hito |
|---|---|---|
| `generador/` | Generador de **datos sintéticos** españoles: pacientes, peticiones, especímenes, resultados e informes | **1** |
| `his/` | Simulador del **HIS** de la clínica: emite `ADT^A01`/`A08` y `OML^O21` por MLLP | 2 |
| `analizador/` | Simulador del **analizador**: emite `ORU^R01` por MLLP | 2 |
| `mllp/` | El **sobre** MLLP, compartido por los dos: son sistemas distintos que hablan el mismo transporte | 2 |
| `receptor/` | Receptor de **notificaciones de `Subscription`**: el sistema al que el laboratorio entrega | 3 |

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

## Los dos simuladores v2

Hablan **MLLP sobre TLS** contra el motor de integración (`integracion/`, puerto 2575). En
desarrollo el certificado es autofirmado, así que la verificación va **apagada** por defecto y se
enciende con `--verificar-certificado`; `--sin-tls` habla en claro y solo sirve para depurar en
local, porque el plano de sistemas va cifrado (D4).

```bash
# El HIS: primero la filiación, luego la petición.
python -m his --mensaje adt --evento A01 --nhc 70000001
python -m his --mensaje oml --volante VOL20260806001 --acceso ACC70000001 --pruebas GLU,CREA,K

# El analizador: el resultado bruto del tubo.
python -m analizador --acceso ACC70000001 --medidas 2345-7:92:mg/dL,2160-0:0.9:mg/dL
```

- **`MSH-10` determinista y `--repetir`.** El identificador por defecto se deriva del contenido, así
  que repetir la misma orden manda el **mismo** `MSH-10`: es lo que ejercita la deduplicación del
  motor sin tener que copiar nada a mano. `--repetir 3` lo manda tres veces de una tacada.
- **El HIS pide en el dialecto del laboratorio** (`99HISPALIS`) y **el analizador informa en LOINC**,
  que es lo realista: un analizador comercial no conoce el catálogo de esta casa. Los dos aceptan
  `--catalogo` para probar el camino contrario, y en ambos casos quien traduce es el `ConceptMap` de
  la guía, nunca una tabla escrita a mano.
- **`--charset` decide `MSH-18` y cómo se codifica el cable**, no solo lo que se declara. Con
  `MUÑOZ DE LA TORRE` de apellido por defecto, un desajuste entre las dos cosas se ve en el acuse.
- **Un `ORU^R01` que entra no está validado.** `OBX-11 = F` es «final del analizador»; la validación
  es de un facultativo y va por el otro camino.

## El receptor de notificaciones (hito 3)

El otro extremo de una `Subscription` de R5. No es un doble del cliente HTTP del backend: es un
tercero al otro lado de un puerto, y por eso puede aceptar, negarse o no estar — que es lo que hace
falta para comprobar el corte.

Comprueba las tres cosas que un receptor de verdad tendría que comprobar:

1. **La firma** cuadra con el secreto compartido, y la marca de tiempo no es de ayer. Sin clave, el
   receptor **no arranca**: aceptar sin firma es aceptar de cualquiera.
2. **La carga es `id-only`.** Una entrada con el recurso dentro se rechaza con `400`. Un receptor que
   se traga un recurso que no pidió acaba almacenando historia clínica sin saber de dónde salió.
3. **No falta ningún `eventNumber`.** Para eso existe el número: con entrega «al menos una vez» y un
   canal que puede caerse, no hay otra forma de enterarse de lo que **no** llegó. Lo que falte se
   recupera con `Subscription/{id}/$events`.

```bash
# dentro del compose, detrás de su perfil
docker compose -f ../infra/compose/docker-compose.yml --profile notificaciones up -d receptor
docker compose -f ../infra/compose/docker-compose.yml logs -f receptor

# o suelto
HISPALIS_CLAVE_HIS=$(openssl rand -hex 32) python -m receptor --puerto 8090
```
