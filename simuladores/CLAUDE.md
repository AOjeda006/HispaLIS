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

## Reglas del generador (las que lo hacen útil o inútil)

- **Consume el mismo `CodeSystem` y `ConceptMap` que el sistema, nunca una lista paralela.** Si se
  desvía, genera datos que solo valen para sí mismo — y el `ConceptMap` deja de estar probado.
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
python -m generador --seed 42 --pacientes 100
```
