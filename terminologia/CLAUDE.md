# CLAUDE.md — `terminologia/` (servidor de terminología y su cargador)

> Complementa al `CLAUDE.md` **raíz**, que siempre está cargado.

## Memoria — convenciones de este componente

@../../BibliotecaDocumentacion/interoperabilidad/terminologia/convenciones.md
@../../BibliotecaDocumentacion/stacks/python/convenciones.md
@../../BibliotecaDocumentacion/herramientas/docker.md

---

## Qué hay aquí y qué no

| | Qué es |
|---|---|
| `hapi/application.yaml` | La configuración del servidor. **La imagen no se construye:** es `hapiproject/hapi` tal cual |
| `cargador/` | Lo que sube al servidor los subconjuntos curados, **por `PUT` de la API estándar** |
| `tests/` | Los tests del cargador, contra mini-*releases* sintéticas |

**Lo que no hay, y no puede haber:** ni una release de terminología licenciada. LOINC, THO y SNOMED
viven **fuera del repositorio** y se montan en el arranque del servicio.

## D14 en una frase: el servidor es intercambiable

Todo lo que el sistema le pide al servidor son las **cuatro operaciones del capítulo de terminología
de FHIR** — `$expand`, `$lookup`, `$validate-code` y `$translate` —, y todo lo que se le sube entra
por `PUT [base]/[tipo]/[id]`. **Ni una operación propietaria.** En particular, no se usa
`$upload-external-code-system`, que es de HAPI: migrar a Snowstorm u Ontoserver tiene que ser cambiar
`HISPALIS_TERMINOLOGIA` y nada más. Si algo del sistema empieza a depender de una particularidad de
HAPI, eso es un fallo, no una optimización.

## Licencias — la regla que decide qué se puede tocar

| Fuente | Se puede redistribuir | Qué implica aquí |
|---|---|---|
| **LOINC 2.82** | Sí, **si cada copia lleva el aviso de copyright y la versión, y no se altera el contenido de ningún campo** | El `CodeSystem` que se sube lleva `copyright` y `version`, y el `LONG_COMMON_NAME` va **intacto** (ADR-0009) |
| **THO 7.3.0** | Sí (CC0) | Se extraen solo los sistemas que la guía cita |
| **SNOMED CT Edición Española** | **No.** Gratuita previa licencia del Ministerio, **sin redistribución** | Ni un fichero en el repo. Se monta desde fuera; si no está, el cargador **avisa en voz alta** y sigue |

**Declara siempre la versión exacta del *release* que cargas.** Sin eso los `display` dejan de ser
reproducibles: la misma consulta da un nombre distinto contra otro servidor y nadie sabe por qué. La
de SNOMED **se deduce del propio release** —del *refset* de dependencia de módulos—, no se escribe a
mano.

## El subconjunto curado se DEDUCE de la guía, no se escribe

`cargador/curado.py` recorre **todos** los recursos que produce SUSHI y recoge cada pareja
`system`/`code` que aparezca, a cualquier profundidad. No hay una lista de códigos en ningún sitio:
si alguien añade un LOINC a un perfil o un SNOMED a un `ValueSet`, el cargador lo sube solo. Una lista
escrita a mano aquí sería la misma lista paralela que prohíbe el invariante 4, solo que en el sitio
donde más caro sale.

Los subconjuntos se declaran `content: fragment`, que es lo que son. Declararlos `complete` sería
mentir en un elemento que otros servidores usan para decidir si pueden expandir.

## Trampas medidas contra HAPI 8.10 (no reincidir)

- **La imagen es *distroless*:** solo lleva `java`. Un `healthcheck` con `curl` no falla por estar el
  servidor mal, falla por no existir `curl` — y el servicio se queda «unhealthy» con el log en verde.
  Quien espera terminología espera al **cargador**, que además es la condición correcta.
- **`$expand` necesita el índice de texto completo.** Sin Hibernate Search, contesta
  `HSEARCH800001: Hibernate Search was not initialized`; las otras tres operaciones funcionan igual.
- **⚠️ `count=0` no significa lo que dice la norma, y falla de forma intermitente.** La norma define
  `count=0` como «devuélveme solo el total, sin códigos»; HAPI lo interpreta como «máximo 0 códigos»
  y aborta con `HAPI-0831: produced too many codes (maximum 0)`. Y **solo lo hace mientras el
  `ValueSet` está `NOT_EXPANDED`**, porque la expansión en memoria es la que aplica el límite: contra
  un servidor que ya tenía la pre-expansión hecha, la misma llamada devuelve el total tan tranquila.
  Pasa siempre un `count` real. Detalle en `../docs/adr/adr-0026-…`.
- **El dialecto tiene que ser el de HAPI** (`HapiFhirPostgres94Dialect`), o el arranque avisa de que
  «dialect is not a HAPI FHIR dialect» y sigue con medio servidor.
- **`$lookup` de LOINC exige `version`** cuando hay un `CodeSystem` de LOINC cargado como fragmento:
  sin ella responde `HAPI-1738: Unable to find code[…]`.
- **La vuelta del `$translate`:** R5 la define con `targetCode`/`targetCoding` y **HAPI no la
  implementa** — contesta `HAPI-1154`. Lo que entiende es `reverse=true`, que es la forma de R4. Los
  clientes piden **las dos, la de R5 primero**.
- **HAPI contesta `match.equivalence`** con códigos de R4 (`narrower`) en vez de `match.relationship`
  de R5. Hay que leer las dos formas.

## Comandos

```bash
cd terminologia
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
pytest
ruff check . && ruff format --check .

# Levantar el servidor y cargarlo (desde la raíz del repo)
docker compose -f infra/compose/docker-compose.yml up -d terminologia terminologia-carga
curl 'http://localhost:8086/fhir/ValueSet/$expand?url=https://aojeda006.github.io/HispaLIS/fhir/ValueSet/pruebas-del-catalogo&count=1000' | head -c 400
```
