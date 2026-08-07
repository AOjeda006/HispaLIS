"""La carga habla FHIR REST y nada más, contra un servidor HTTP de verdad.

El servidor de estos tests no es un servidor FHIR: es un HTTP que apunta lo que recibe. Lo que
se comprueba aquí no es que HAPI entienda el recurso —eso se comprueba contra HAPI, en el
`compose` y en los tests del backend— sino **qué verbo y qué rutas usa el cargador**, que es lo
que decide si el servidor de terminología es intercambiable o no.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import ClassVar

import pytest

from cargador.publicacion import ServidorNoDisponibleError, publicar

RECURSO = {
    "resourceType": "CodeSystem",
    "id": "loinc",
    "url": "http://loinc.org",
    "version": "2.82",
}


class _Apuntador(BaseHTTPRequestHandler):
    recibido: ClassVar[list[tuple[str, str]]] = []
    respuesta: int = 201
    cuerpo: bytes = b""

    def do_PUT(self) -> None:
        longitud = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(longitud)
        type(self).recibido.append((self.command, self.path))
        self.send_response(type(self).respuesta)
        self.send_header("Content-Type", "application/fhir+json")
        self.end_headers()
        self.wfile.write(type(self).cuerpo)

    def log_message(self, formato: str, *argumentos: object) -> None:
        """Silencio: el log del servidor de pruebas no aporta nada al de pytest."""


@pytest.fixture
def servidor():
    _Apuntador.recibido = []
    _Apuntador.respuesta = 201
    _Apuntador.cuerpo = b""
    httpd = HTTPServer(("127.0.0.1", 0), _Apuntador)
    hilo = threading.Thread(target=httpd.serve_forever, daemon=True)
    hilo.start()
    yield f"http://127.0.0.1:{httpd.server_port}/fhir"
    httpd.shutdown()
    httpd.server_close()


def test_se_sube_con_put_a_la_ruta_estandar_del_recurso(servidor: str) -> None:
    """`PUT [tipo]/[id]` es FHIR REST del capítulo 2, y lo entiende cualquier servidor."""
    publicar(servidor, [RECURSO])

    assert _Apuntador.recibido == [("PUT", "/fhir/CodeSystem/loinc")]


def test_cargar_dos_veces_deja_el_servidor_igual(servidor: str) -> None:
    """El `compose` repite la carga en cada `up`: no puede acabar con dos LOINC."""
    publicar(servidor, [RECURSO])
    publicar(servidor, [RECURSO])

    assert {ruta for _, ruta in _Apuntador.recibido} == {"/fhir/CodeSystem/loinc"}


def test_no_se_usa_ninguna_operacion_propia_del_servidor(servidor: str) -> None:
    """HAPI tiene `$upload-external-code-system`; usarla ataría el proyecto a HAPI (D14)."""
    publicar(servidor, [RECURSO])

    assert all("$" not in ruta for _, ruta in _Apuntador.recibido)


def test_un_rechazo_del_servidor_para_la_carga_con_su_diagnostico(servidor: str) -> None:
    """Una terminología a medias haría rechazar códigos buenos: mejor no arrancar."""
    _Apuntador.respuesta = 422
    _Apuntador.cuerpo = json.dumps(
        {
            "resourceType": "OperationOutcome",
            "issue": [{"severity": "error", "diagnostics": "URL duplicada"}],
        }
    ).encode("utf-8")

    with pytest.raises(ServidorNoDisponibleError, match="URL duplicada"):
        publicar(servidor, [RECURSO])


def test_si_el_servidor_no_esta_se_dice_cual_es_el_servicio(tmp_path) -> None:
    with pytest.raises(ServidorNoDisponibleError, match="terminología"):
        publicar("http://127.0.0.1:1/fhir", [RECURSO], espera=1.0)
