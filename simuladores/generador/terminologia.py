"""El catálogo de pruebas del laboratorio, preguntado al servidor de terminología.

**Esto es el invariante D15 hecho código.** El generador resuelve contra el *mismo* servidor que el
backend y el motor, no contra una lista paralela escrita aquí. La diferencia no es de estilo: con
una lista propia, el generador produce datos que solo valen para sí mismo, el `ConceptMap` deja de
estar probado por nadie, y el día que se añada una prueba al catálogo los datos sintéticos siguen
tan campantes con el catálogo viejo — que es la forma más silenciosa de que un juego de pruebas
deje de probar lo que dice probar.

Hasta el ítem 33 los artefactos se leían de `ig/fsh-generated/`. Ahora se preguntan por la API
estándar (D14), que es lo que hace que el servidor sea intercambiable: aquí no hay ni una operación
propietaria ni un nombre de fichero.

Las cuatro operaciones y para qué sirve cada una:

- `$expand` — qué pruebas oferta el catálogo. Es la única forma estándar de pedir «el conjunto
  entero», y el generador la necesita de verdad: elige pruebas al azar sobre todo el catálogo.
- `$lookup` — el nombre en español de cada prueba y su unidad UCUM, que el `CodeSystem` declara como
  propiedad `unidad-ucum`.
- `$translate` — el LOINC equivalente.
- `$validate-code` — los tipos de muestra que el laboratorio acepta.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

#: Dónde está el servidor de terminología. El mismo nombre que en el backend y el motor: los tres
#: resuelven contra la misma autoridad, y eso tiene que verse también en la configuración.
VARIABLE_ENTORNO = "HISPALIS_TERMINOLOGIA"

SERVIDOR_POR_DEFECTO = "http://localhost:8086/fhir"

SYSTEM_CATALOGO = "https://aojeda006.github.io/HispaLIS/fhir/CodeSystem/catalogo-pruebas"
CONJUNTO_DE_PRUEBAS = "https://aojeda006.github.io/HispaLIS/fhir/ValueSet/pruebas-del-catalogo"
CONJUNTO_TIPOS_DE_MUESTRA = "https://aojeda006.github.io/HispaLIS/fhir/ValueSet/tipos-muestra"
MAPA_A_LOINC = "https://aojeda006.github.io/HispaLIS/fhir/ConceptMap/catalogo-a-loinc"

SYSTEM_UCUM = "http://unitsofmeasure.org"
SYSTEM_LOINC = "http://loinc.org"
SYSTEM_SNOMED = "http://snomed.info/sct"

#: Propiedad del `CodeSystem` que declara en qué unidad emite el laboratorio cada prueba.
PROPIEDAD_UNIDAD = "unidad-ucum"

#: Cuánto se espera al servidor. Generar es un proceso por lotes, no una petición interactiva: más
#: vale esperar diez segundos que fallar por un servidor que estaba arrancando.
ESPERA_SEGUNDOS = 10

#: Cómo se escribe una unidad en un informe español cuando no coincide con su código UCUM.
#:
#: No es una lista paralela de terminología —la que prohíbe D15—: los códigos siguen viniendo todos
#: del servidor, y esto solo dice cómo se *imprime* uno de ellos. Se queda corta a propósito y cae
#: con elegancia: una unidad que no esté aquí se imprime con su propio código UCUM, así que añadir
#: una prueba al catálogo nunca rompe nada.
UNIDADES_IMPRESAS = {
    "u[IU]/mL": "µUI/mL",
    "10*3/uL": "10³/µL",
}


class TerminologiaNoDisponibleError(RuntimeError):
    """El servidor de terminología no contesta, o no tiene cargado el catálogo."""


@dataclass(frozen=True, slots=True)
class Prueba:
    """Una prueba del catálogo local, con su unidad y su equivalente LOINC.

    Attributes:
        codigo: Código del catálogo local (`GLU`, `TSH`…).
        display: Nombre de la prueba, en español, tal y como lo publica la guía.
        unidad_ucum: Código UCUM de la unidad, o `None` si la prueba es cualitativa.
        loinc: Código LOINC equivalente según el `ConceptMap`, si lo tiene.
        loinc_display: Nombre oficial LOINC, que va en inglés porque LOINC lo publica así.
    """

    codigo: str
    display: str
    unidad_ucum: str | None
    loinc: str | None
    loinc_display: str | None

    @property
    def es_cuantitativa(self) -> bool:
        """Indica si la prueba se informa con una cifra y una unidad.

        Lo decide la unidad declarada en el catálogo y no una lista de códigos: una prueba nueva
        queda clasificada sola, sin tocar este módulo.
        """
        return self.unidad_ucum is not None

    @property
    def unidad_impresa(self) -> str | None:
        """La unidad tal y como se escribe en el informe."""
        if self.unidad_ucum is None:
            return None
        return UNIDADES_IMPRESAS.get(self.unidad_ucum, self.unidad_ucum)


@dataclass(frozen=True, slots=True)
class Catalogo:
    """El catálogo de pruebas completo, en el orden en que lo devuelve la expansión.

    El orden importa: es una tupla y no un conjunto porque el generador elige pruebas al azar sobre
    ella, y un recorrido con orden indefinido haría irreproducible la salida aun con la misma
    semilla.
    """

    system: str
    pruebas: tuple[Prueba, ...]

    def __getitem__(self, codigo: str) -> Prueba:
        for prueba in self.pruebas:
            if prueba.codigo == codigo:
                return prueba
        raise KeyError(f"«{codigo}» no está en el catálogo de pruebas del laboratorio.")

    def __contains__(self, codigo: object) -> bool:
        return any(prueba.codigo == codigo for prueba in self.pruebas)

    def __len__(self) -> int:
        return len(self.pruebas)

    @property
    def codigos(self) -> tuple[str, ...]:
        """Los códigos de todas las pruebas, en el orden del catálogo."""
        return tuple(prueba.codigo for prueba in self.pruebas)

    @property
    def cuantitativas(self) -> tuple[Prueba, ...]:
        """Las pruebas que se informan con cifra y unidad."""
        return tuple(prueba for prueba in self.pruebas if prueba.es_cuantitativa)

    @property
    def cualitativas(self) -> tuple[Prueba, ...]:
        """Las pruebas que se informan con un concepto codificado, no con una cifra."""
        return tuple(prueba for prueba in self.pruebas if not prueba.es_cuantitativa)


def cargar_catalogo(servidor: str | None = None) -> Catalogo:
    """Pregunta el catálogo de pruebas al servidor de terminología.

    Args:
        servidor: Base FHIR del servidor. Por defecto, lo que indique la variable de entorno
            `HISPALIS_TERMINOLOGIA`, o el puerto del `compose`.

    Returns:
        El catálogo completo, con nombre en español, unidad y traducción a LOINC ya resueltas.

    Raises:
        TerminologiaNoDisponibleError: Si el servidor no contesta o no tiene el catálogo cargado.
    """
    base = _base(servidor)
    codigos = _expandir(base, CONJUNTO_DE_PRUEBAS)
    if not codigos:
        raise TerminologiaNoDisponibleError(
            f"El servidor de terminología de «{base}» no oferta ni una prueba. No se genera con un "
            f"catálogo vacío: comprueba que el cargador de terminología haya terminado."
        )

    pruebas = tuple(_prueba(base, codigo) for codigo in codigos)
    return Catalogo(system=SYSTEM_CATALOGO, pruebas=pruebas)


def cargar_tipos_de_muestra(servidor: str | None = None) -> frozenset[str]:
    """Pregunta al servidor qué tipos de muestra acepta el laboratorio.

    Existe para que un test pueda comprobar que los tipos que usa el generador salen de la misma
    autoridad que los del sistema y no de la cabeza de nadie. Es la misma razón que el catálogo: lo
    que no se cruza contra la fuente, se desvía.

    Returns:
        Los códigos SNOMED admitidos.

    Raises:
        TerminologiaNoDisponibleError: Si el servidor no contesta.
    """
    return frozenset(_expandir(_base(servidor), CONJUNTO_TIPOS_DE_MUESTRA))


def admite_tipo_de_muestra(codigo_snomed: str, servidor: str | None = None) -> bool:
    """Comprueba un tipo de muestra con `$validate-code`, que es preguntar en vez de mirar la lista.

    Es la forma correcta de comprobar **un** código: no obliga al servidor a expandir el conjunto
    entero y funciona igual con un conjunto definido por filtro, que es donde una expansión traída
    al cliente se queda corta.
    """
    salida = _operacion(
        _base(servidor),
        "ValueSet/$validate-code",
        {"url": CONJUNTO_TIPOS_DE_MUESTRA, "system": SYSTEM_SNOMED, "code": codigo_snomed},
    )
    return bool(_parametro(salida, "result", "valueBoolean"))


def _prueba(base: str, codigo: str) -> Prueba:
    """Una prueba, resuelta con `$lookup` para el nombre y `$translate` para el LOINC."""
    salida = _operacion(base, "CodeSystem/$lookup", {"system": SYSTEM_CATALOGO, "code": codigo})
    display = _parametro(salida, "display", "valueString")
    if not display:
        raise TerminologiaNoDisponibleError(
            f"El servidor conoce «{codigo}» en la expansión pero no sabe cómo se llama. El "
            f"catálogo está cargado a medias y generar con él daría informes sin nombre de prueba."
        )

    loinc, loinc_display = _a_loinc(base, codigo)
    return Prueba(
        codigo=codigo,
        display=display,
        unidad_ucum=_unidad(salida),
        loinc=loinc,
        loinc_display=loinc_display,
    )


def _a_loinc(base: str, codigo: str) -> tuple[str | None, str | None]:
    salida = _operacion(
        base,
        "ConceptMap/$translate",
        {
            "url": MAPA_A_LOINC,
            "system": SYSTEM_CATALOGO,
            "sourceCode": codigo,
            "targetSystem": SYSTEM_LOINC,
        },
    )
    if not _parametro(salida, "result", "valueBoolean"):
        return (None, None)

    for parametro in salida.get("parameter", []):
        if parametro.get("name") != "match":
            continue
        for parte in parametro.get("part", []):
            concepto = parte.get("valueCoding")
            if parte.get("name") != "concept" or not concepto:
                continue
            if concepto.get("system") == SYSTEM_LOINC:
                # El `display` se copia tal cual: la licencia de LOINC prohíbe alterarlo, y por eso
                # llega en inglés. El nombre que se lee en un informe español es el del catálogo.
                return (concepto.get("code"), concepto.get("display"))
    return (None, None)


def _unidad(salida: dict) -> str | None:
    """La unidad UCUM, leída de la propiedad `unidad-ucum` que devuelve el `$lookup`."""
    for parametro in salida.get("parameter", []):
        if parametro.get("name") != "property":
            continue
        partes = {parte.get("name"): parte for parte in parametro.get("part", [])}
        if partes.get("code", {}).get("valueCode") != PROPIEDAD_UNIDAD:
            continue
        return partes.get("value", {}).get("valueCoding", {}).get("code")
    return None


def _expandir(base: str, conjunto: str) -> tuple[str, ...]:
    """Los códigos de un `ValueSet`, en el orden en que los devuelve el servidor."""
    salida = _operacion(base, "ValueSet/$expand", {"url": conjunto, "count": "1000"})
    contenido = salida.get("expansion", {}).get("contains", [])
    return tuple(concepto["code"] for concepto in contenido if "code" in concepto)


def _operacion(base: str, operacion: str, parametros: dict[str, str]) -> dict:
    """Invoca una operación de terminología por GET y devuelve el recurso de la respuesta.

    Se usa `GET` y no `POST` a propósito: las cuatro operaciones son idempotentes y sin efectos, la
    especificación las publica así, y una URL se puede pegar en un navegador para depurar. Aquí no
    viaja ni un dato de paciente — son códigos de catálogo—, así que la regla de «nada de PHI en
    URLs» no está en juego.
    """
    url = f"{base}/{operacion}?" + urllib.parse.urlencode(parametros)
    peticion = urllib.request.Request(url, headers={"Accept": "application/fhir+json"})
    try:
        with urllib.request.urlopen(peticion, timeout=ESPERA_SEGUNDOS) as respuesta:
            return json.loads(respuesta.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detalle = error.read().decode("utf-8", errors="replace")[:400]
        raise TerminologiaNoDisponibleError(
            f"El servidor de terminología rechazó «{operacion}»: HTTP {error.code}. {detalle}"
        ) from error
    except OSError as error:
        raise TerminologiaNoDisponibleError(
            f"No se pudo hablar con el servidor de terminología de «{base}»: {error}. Levanta la "
            f"pila con «docker compose up -d terminologia terminologia-carga», o apunta a otro "
            f"servidor con la variable de entorno {VARIABLE_ENTORNO}."
        ) from error


def _parametro(salida: dict, nombre: str, tipo: str):
    for parametro in salida.get("parameter", []):
        if parametro.get("name") == nombre and tipo in parametro:
            return parametro[tipo]
    return None


def _base(servidor: str | None) -> str:
    if servidor:
        return servidor.rstrip("/")
    return os.environ.get(VARIABLE_ENTORNO, SERVIDOR_POR_DEFECTO).rstrip("/")
