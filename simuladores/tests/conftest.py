"""Arnés común de los tests: un servidor de terminología cargado con la guía.

El generador ya no lee ficheros: pregunta al servidor de terminología por la API estándar (D14). Los
tests, por tanto, necesitan uno. El de aquí responde las cuatro operaciones **por HTTP** a partir
de los artefactos que produce SUSHI, así que lo que se prueba sigue siendo la terminología de
verdad —la misma que carga el `compose`—, no una copia escrita en el test.

Aquí es donde debe vivir la lectura de ficheros: el que sabe dónde están los artefactos es quien
sirve la terminología, no quien la consume.
"""

from __future__ import annotations

import json
import os
import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

from generador.terminologia import (
    CONJUNTO_DE_PRUEBAS,
    CONJUNTO_TIPOS_DE_MUESTRA,
    PROPIEDAD_UNIDAD,
    SYSTEM_CATALOGO,
    SYSTEM_LOINC,
    SYSTEM_UCUM,
    VARIABLE_ENTORNO,
)

NOMBRE_CODESYSTEM = "CodeSystem-catalogo-pruebas.json"
NOMBRE_CONCEPTMAP = "ConceptMap-catalogo-a-loinc.json"
CONJUNTOS = {
    CONJUNTO_DE_PRUEBAS: "ValueSet-pruebas-del-catalogo.json",
    CONJUNTO_TIPOS_DE_MUESTRA: "ValueSet-tipos-muestra.json",
}


class TerminologiaDeLaGuia:
    """Contesta `$expand`, `$lookup`, `$translate` y `$validate-code` desde lo que da SUSHI."""

    def __init__(self, recursos: Path) -> None:
        self.catalogo = _leer(recursos / NOMBRE_CODESYSTEM)
        self.mapa = _leer(recursos / NOMBRE_CONCEPTMAP)
        self.conjuntos = {url: _leer(recursos / fichero) for url, fichero in CONJUNTOS.items()}

    def expand(self, parametros: dict[str, str]) -> dict:
        conjunto = self.conjuntos.get(parametros.get("url", ""))
        if conjunto is None:
            return _problema(f"No hay ningún ValueSet con la URL «{parametros.get('url')}».")
        return {
            "resourceType": "ValueSet",
            "url": conjunto["url"],
            "expansion": {"contains": list(self._contenido(conjunto))},
        }

    def lookup(self, parametros: dict[str, str]) -> dict:
        if parametros.get("system") != SYSTEM_CATALOGO:
            return _problema("Este servidor de prueba solo tiene el catálogo del laboratorio.")
        concepto = self._concepto(parametros.get("code", ""))
        if concepto is None:
            return _problema(f"No se conoce el código «{parametros.get('code')}».")

        salida = [
            {"name": "name", "valueString": self.catalogo["name"]},
            {"name": "display", "valueString": concepto["display"]},
        ]
        unidad = _unidad(concepto)
        if unidad is not None:
            salida.append(
                {
                    "name": "property",
                    "part": [
                        {"name": "code", "valueCode": PROPIEDAD_UNIDAD},
                        {"name": "value", "valueCoding": {"system": SYSTEM_UCUM, "code": unidad}},
                    ],
                }
            )
        return {"resourceType": "Parameters", "parameter": salida}

    def translate(self, parametros: dict[str, str]) -> dict:
        # HAPI 8.10 rechaza el `targetCode` de R5; este servidor hace lo mismo para que el
        # cliente no pase los tests por un camino que en el `compose` no se recorre.
        if "targetCode" in parametros or "targetCoding" in parametros:
            return _problema(
                "HAPI-1154: One (and only one) of the in parameters (code, coding, "
                "codeableConcept) must be provided, to identify the code that is to be translated."
            )
        destino = self._destino(parametros.get("sourceCode", ""))
        if destino is None:
            return {
                "resourceType": "Parameters",
                "parameter": [{"name": "result", "valueBoolean": False}],
            }

        return {
            "resourceType": "Parameters",
            "parameter": [
                {"name": "result", "valueBoolean": True},
                {
                    "name": "match",
                    "part": [
                        {"name": "equivalence", "valueCode": _relacion(destino)},
                        {
                            "name": "concept",
                            "valueCoding": {
                                "system": SYSTEM_LOINC,
                                "code": destino["code"],
                                "display": destino["display"],
                            },
                        },
                    ],
                },
            ],
        }

    def validate_code(self, parametros: dict[str, str]) -> dict:
        conjunto = self.conjuntos.get(parametros.get("url", ""))
        esta = conjunto is not None and any(
            concepto["code"] == parametros.get("code")
            and concepto["system"] == parametros.get("system")
            for concepto in self._contenido(conjunto)
        )
        return {
            "resourceType": "Parameters",
            "parameter": [{"name": "result", "valueBoolean": esta}],
        }

    def _contenido(self, conjunto: dict) -> Iterator[dict]:
        """Los conceptos de un `ValueSet`: los enumerados, o el catálogo si se incluye entero."""
        for inclusion in conjunto["compose"]["include"]:
            if "concept" in inclusion:
                for concepto in inclusion["concept"]:
                    yield {"system": inclusion["system"], "code": concepto["code"]}
            elif inclusion["system"] == SYSTEM_CATALOGO:
                for concepto in self.catalogo["concept"]:
                    yield {"system": SYSTEM_CATALOGO, "code": concepto["code"]}

    def _concepto(self, codigo: str) -> dict | None:
        return next((c for c in self.catalogo["concept"] if c["code"] == codigo), None)

    def _destino(self, codigo: str) -> dict | None:
        for grupo in self.mapa.get("group", []):
            for elemento in grupo.get("element", []):
                if elemento["code"] == codigo and elemento.get("target"):
                    return elemento["target"][0]
        return None


def _relacion(destino: dict) -> str:
    # HAPI traduce las relaciones de R5 a los códigos de R4 al contestar.
    return "equivalent" if destino.get("relationship") == "equivalent" else "narrower"


def _unidad(concepto: dict) -> str | None:
    for propiedad in concepto.get("property", []):
        if propiedad.get("code") == PROPIEDAD_UNIDAD:
            return propiedad["valueCoding"]["code"]
    return None


def _problema(diagnostico: str) -> dict:
    return {
        "resourceType": "OperationOutcome",
        "issue": [{"severity": "error", "code": "processing", "diagnostics": diagnostico}],
    }


def _leer(ruta: Path) -> dict:
    if not ruta.exists():
        raise FileNotFoundError(
            f"No se encuentra «{ruta}». El servidor de terminología de los tests se carga con lo "
            f"que produce la guía: ejecuta «npx fsh-sushi .» dentro de «ig/», o apunta a otro "
            f"directorio con HISPALIS_GUIA."
        )
    return json.loads(ruta.read_text(encoding="utf-8"))


def _directorio_de_la_guia() -> Path:
    indicado = os.environ.get("HISPALIS_GUIA")
    if indicado:
        return Path(indicado)
    # `tests/conftest.py` → `tests/` → `simuladores/` → la raíz.
    return Path(__file__).resolve().parents[2] / "ig" / "fsh-generated" / "resources"


def _manejador(terminologia: TerminologiaDeLaGuia) -> type[BaseHTTPRequestHandler]:
    class Manejador(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            partes = urlparse(self.path)
            parametros = {clave: valores[0] for clave, valores in parse_qs(partes.query).items()}
            operacion = partes.path.rsplit("/", 1)[-1]

            respuestas = {
                "$expand": terminologia.expand,
                "$lookup": terminologia.lookup,
                "$translate": terminologia.translate,
                "$validate-code": terminologia.validate_code,
            }
            if operacion not in respuestas:
                cuerpo = _problema(f"Este servidor de prueba no implementa {operacion}.")
            else:
                cuerpo = respuestas[operacion](parametros)

            codificado = json.dumps(cuerpo, ensure_ascii=False).encode("utf-8")
            self.send_response(400 if cuerpo["resourceType"] == "OperationOutcome" else 200)
            self.send_header("Content-Type", "application/fhir+json;charset=utf-8")
            self.send_header("Content-Length", str(len(codificado)))
            self.end_headers()
            self.wfile.write(codificado)

        def log_message(self, formato: str, *argumentos: object) -> None:
            """Sin ruido: escribir en stderr ensuciaría la salida de pytest."""

    return Manejador


@pytest.fixture(scope="session", autouse=True)
def servidor_de_terminologia() -> Iterator[str]:
    """Levanta el servidor y apunta a él la variable de entorno que lee el generador.

    Es `autouse` porque el catálogo lo necesitan casi todos los tests, y `session` porque cargar los
    artefactos de la guía en cada uno multiplicaría el tiempo sin ganar aislamiento: el servidor no
    tiene estado que un test pueda ensuciar.
    """
    terminologia = TerminologiaDeLaGuia(_directorio_de_la_guia())
    servidor = ThreadingHTTPServer(("127.0.0.1", 0), _manejador(terminologia))
    hilo = threading.Thread(target=servidor.serve_forever, daemon=True)
    hilo.start()

    url = f"http://127.0.0.1:{servidor.server_address[1]}/fhir"
    anterior = os.environ.get(VARIABLE_ENTORNO)
    os.environ[VARIABLE_ENTORNO] = url
    try:
        yield url
    finally:
        if anterior is None:
            del os.environ[VARIABLE_ENTORNO]
        else:
            os.environ[VARIABLE_ENTORNO] = anterior
        servidor.shutdown()
        servidor.server_close()
