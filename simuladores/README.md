# `simuladores/` — generador de datos sintéticos, HIS y analizador

| Carpeta | Qué es | Hito |
|---|---|---|
| `generador/` | Generador de **datos sintéticos** españoles: pacientes, peticiones, especímenes y resultados | **1** |
| `his/` | Simulador del **HIS** de la clínica: emitirá `ADT^A01`/`A08` y `OML^O21` por MLLP | 2 |
| `analizador/` | Simulador del **analizador**: emitirá `ORU^R01` por MLLP | 2 |

> **Nunca datos reales de pacientes.** Todo lo que sale de aquí es sintético, en cualquier entorno.

## Puesta en marcha

```bash
cd simuladores
python -m venv .venv && source .venv/bin/activate   # en Windows: .venv\Scripts\activate
pip install -e ".[dev]"
```

## Comandos

```bash
pytest                                  # tests
ruff check . && ruff format --check .   # lint y formato
python -m generador --seed 42 --pacientes 100
```

## Reproducibilidad

El generador hace **doble función**: juego de datos de prueba y arnés de carga. La misma semilla
produce **exactamente** la misma salida — sin eso no sirve para comparar dos ejecuciones. Por eso la
configuración de una ejecución es inmutable y la semilla es un parámetro de primera clase.

La generación de datos propiamente dicha corresponde al **ítem 13** del checklist de
[`../docs/PLAN.md`](../docs/PLAN.md); por ahora solo está el armazón de configuración.
