"""Punto de entrada del SVEA simulado: `python -m svea --puerto 8091 --modo acusa`."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from collections.abc import Sequence
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from svea import DeclaracionRechazadaError, LibroDeRegistro, leer_la_declaracion, registrar

PUERTO_POR_DEFECTO = 8091
RUTA_POR_DEFECTO = "/declaraciones"

#: Las cuatro respuestas que el laboratorio distingue, y por qué cada una está aquí.
#:
#: No son «modos de prueba» decorativos: son los cuatro finales que el notificador tiene que saber
#: contar distintos, y sin poder provocarlos a mano solo se ejercita el bueno.
MODOS = {
    "acusa": "Registra y devuelve el número. Es el camino normal.",
    "rechaza": "422: no lo acepta. El laboratorio NO reintenta y la declaración queda rechazada.",
    "sin-registro": "200 sin número: se recibió y NO consta. Es el que más fácil se da por bueno.",
    "silencio": "503: el destinatario está caído. Se reintenta y la declaración sigue pendiente.",
}

LOG = logging.getLogger("svea")


def construir_analizador() -> argparse.ArgumentParser:
    """Define la interfaz de línea de órdenes del SVEA simulado."""
    analizador = argparse.ArgumentParser(
        prog="svea",
        description=(
            "Simula el servicio de declaraciones del SVEA: exige que la declaración NO lleve "
            "filiación, que traiga plazo, deduplica los reintentos y devuelve número de registro."
        ),
        epilog="Modos: " + " · ".join(f"{modo}: {que_hace}" for modo, que_hace in MODOS.items()),
    )
    analizador.add_argument("--puerto", type=int, default=PUERTO_POR_DEFECTO)
    analizador.add_argument("--ruta", default=RUTA_POR_DEFECTO)
    analizador.add_argument(
        "--modo",
        choices=sorted(MODOS),
        default="acusa",
        help="Qué contesta a una declaración válida. Sirve para provocar los caminos malos.",
    )
    return analizador


def main(argumentos: Sequence[str] | None = None) -> int:
    """Levanta el servicio de declaraciones y se queda escuchando."""
    opciones = construir_analizador().parse_args(argumentos)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    libro = LibroDeRegistro()
    servidor = ThreadingHTTPServer(("0.0.0.0", opciones.puerto), _manejador(opciones, libro))
    LOG.info(
        "SVEA simulado escuchando en http://0.0.0.0:%d%s (modo «%s»: %s)",
        opciones.puerto,
        opciones.ruta,
        opciones.modo,
        MODOS[opciones.modo],
    )
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        LOG.info(
            "Registradas %d declaraciones, %d fuera de plazo.",
            len(libro.declaraciones),
            len(libro.fuera_de_plazo),
        )
    finally:
        servidor.server_close()
    return 0


def _manejador(
    opciones: argparse.Namespace, libro: LibroDeRegistro
) -> type[BaseHTTPRequestHandler]:
    """Fabrica el manejador con la configuración dentro: `http.server` no la deja pasar."""

    class Manejador(BaseHTTPRequestHandler):
        # `http.server` escribe a stderr con su propio formato y sin nivel. Se redirige al log para
        # que la salida del contenedor sea una sola cosa legible.
        def log_message(self, formato: str, *argumentos: object) -> None:
            LOG.debug(formato, *argumentos)

        def do_GET(self) -> None:
            """El libro de registro, para poder mirarlo con `curl`.

            Lo que devuelve son cuentas y números de registro: ni una referencia al caso, porque
            esto es un listado que se mira en una pantalla y se pega en un ticket.
            """
            if self.path != opciones.ruta:
                self._responder(404)
                return
            self._responder(
                200,
                {
                    "registradas": len(libro.declaraciones),
                    "fuera_de_plazo": libro.fuera_de_plazo,
                    "por_enfermedad": _por_enfermedad(libro),
                },
            )

        def do_POST(self) -> None:
            if self.path != opciones.ruta:
                self._responder(404)
                return

            if opciones.modo == "silencio":
                # Se contesta ANTES de leer nada: un destinatario caído lo está para todos, no solo
                # para las declaraciones bien formadas.
                LOG.warning("Modo «silencio»: se contesta 503 sin mirar la declaración.")
                self._responder(503)
                return

            crudo = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            try:
                # FHIR JSON es UTF-8 por definición, así que otra cosa se rechaza. Pero se rechaza
                # CONTESTANDO: sin este `try`, un cuerpo en Latin-1 revienta el hilo del manejador y
                # el emisor recibe una conexión cortada, que no le dice nada y parece una caída.
                # Salió probando con `curl` desde una consola de Windows, no en los tests.
                cuerpo = crudo.decode("utf-8")
            except UnicodeDecodeError as no_es_utf8:
                LOG.warning("Declaración rechazada (400): el cuerpo no es UTF-8 (%s).", no_es_utf8)
                self._responder(400, {"motivo": "El cuerpo no viene en UTF-8, y FHIR JSON lo es."})
                return

            try:
                declaracion = leer_la_declaracion(cuerpo)
            except DeclaracionRechazadaError as rechazada:
                LOG.warning("Declaración rechazada (%d): %s", rechazada.codigo_http, rechazada)
                self._responder(rechazada.codigo_http, {"motivo": str(rechazada)})
                return

            if opciones.modo == "rechaza":
                motivo = "El código de enfermedad no está en vigor."
                LOG.warning("Modo «rechaza»: se contesta 422 a %s.", declaracion.enfermedad)
                self._responder(422, {"motivo": motivo})
                return

            numero, a_tiempo = registrar(declaracion, libro)
            if not a_tiempo:
                LOG.warning(
                    "Declaración de %s registrada como %s FUERA DE PLAZO (vencía %s). Se registra "
                    "igual: el plazo no extingue la obligación, la hace tardía.",
                    declaracion.enfermedad,
                    numero,
                    declaracion.vencimiento.isoformat(),
                )
            else:
                LOG.info(
                    "Declaración de %s registrada como %s (%s).",
                    declaracion.enfermedad,
                    numero,
                    "urgente" if declaracion.urgente else "ordinaria",
                )

            if opciones.modo == "sin-registro":
                LOG.warning("Modo «sin-registro»: se contesta 200 sin número, así que NO consta.")
                self._responder(200, {})
                return
            self._responder(200, {"registro": numero})

        def _responder(self, codigo: int, cuerpo: dict | None = None) -> None:
            datos = json.dumps(cuerpo or {}, ensure_ascii=False).encode("utf-8")
            self.send_response(codigo)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(datos)))
            self.end_headers()
            self.wfile.write(datos)

    return Manejador


def _por_enfermedad(libro: LibroDeRegistro) -> dict[str, int]:
    """Cuántas declaraciones de cada enfermedad. Es la única estadística que esto sabe hacer."""
    cuenta: dict[str, int] = {}
    for declaracion in libro.declaraciones:
        cuenta[declaracion.enfermedad] = cuenta.get(declaracion.enfermedad, 0) + 1
    return cuenta


if __name__ == "__main__":
    sys.exit(main())
