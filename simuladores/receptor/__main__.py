"""Punto de entrada del receptor: `python -m receptor --puerto 8090 --secreto …`."""

from __future__ import annotations

import argparse
import logging
import os
import sys
from collections.abc import Sequence
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from receptor import NotificacionRechazadaError, Recibidas, comprobar_la_firma, procesar

PUERTO_POR_DEFECTO = 8090
RUTA_POR_DEFECTO = "/notificaciones"

LOG = logging.getLogger("receptor")


def construir_analizador() -> argparse.ArgumentParser:
    """Define la interfaz de línea de órdenes del receptor."""
    analizador = argparse.ArgumentParser(
        prog="receptor",
        description=(
            "Simula el sistema al que el laboratorio entrega sus notificaciones de Subscription: "
            "comprueba la firma, exige que la carga sea `id-only` y detecta los eventos que faltan."
        ),
    )
    analizador.add_argument("--puerto", type=int, default=PUERTO_POR_DEFECTO)
    analizador.add_argument("--ruta", default=RUTA_POR_DEFECTO)
    analizador.add_argument(
        "--secreto",
        default=os.environ.get("HISPALIS_CLAVE_HIS", ""),
        help=(
            "La clave compartida con el laboratorio. Sin ella el receptor NO arranca: aceptar sin "
            "firmar es aceptar de cualquiera."
        ),
    )
    return analizador


def main(argumentos: Sequence[str] | None = None) -> int:
    """Levanta el receptor y se queda escuchando."""
    opciones = construir_analizador().parse_args(argumentos)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if not opciones.secreto:
        LOG.error(
            "Falta la clave compartida (--secreto o HISPALIS_CLAVE_HIS). Un receptor que acepta "
            "sin firma acepta de cualquiera, así que no se arranca."
        )
        return 2

    visto = Recibidas()
    servidor = ThreadingHTTPServer(("0.0.0.0", opciones.puerto), _manejador(opciones, visto))
    LOG.info("Escuchando notificaciones en http://0.0.0.0:%d%s", opciones.puerto, opciones.ruta)
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        LOG.info(
            "Recibidas %d notificaciones; último evento: %d", len(visto.focos), visto.ultimo_evento
        )
    finally:
        servidor.server_close()
    return 0


def _manejador(opciones: argparse.Namespace, visto: Recibidas) -> type[BaseHTTPRequestHandler]:
    """Fabrica el manejador con la configuración dentro: `http.server` no la deja pasar."""

    class Manejador(BaseHTTPRequestHandler):
        # `http.server` escribe a stderr con su propio formato y sin nivel. Se redirige al log para
        # que la salida del contenedor sea una sola cosa legible.
        def log_message(self, formato: str, *argumentos: object) -> None:
            LOG.debug(formato, *argumentos)

        def do_POST(self) -> None:
            if self.path != opciones.ruta:
                self.send_response(404)
                self.end_headers()
                return

            cuerpo = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8")
            try:
                comprobar_la_firma(
                    opciones.secreto,
                    {
                        "X-HispaLIS-Momento": self.headers.get("X-HispaLIS-Momento"),
                        "X-HispaLIS-Firma": self.headers.get("X-HispaLIS-Firma"),
                    },
                    cuerpo,
                )
                referencias = procesar(cuerpo, visto)
            except NotificacionRechazadaError as rechazada:
                # Se contesta con el código, y eso importa: el laboratorio lo apunta como motivo del
                # fallo y acaba saliendo por `$status`. Un receptor que traga en silencio deja al
                # emisor creyendo que todo va bien.
                LOG.warning("Notificación rechazada (%d): %s", rechazada.codigo_http, rechazada)
                self.send_response(rechazada.codigo_http)
                self.end_headers()
                return

            LOG.info(
                "Notificación %d aceptada: %s",
                visto.ultimo_evento,
                ", ".join(referencias) or "sin referencias",
            )
            if visto.huecos:
                LOG.warning(
                    "Faltan eventos: %s. Recupéralos con `$events`.",
                    "; ".join(f"del {desde} al {hasta}" for desde, hasta in visto.huecos),
                )
            self.send_response(200)
            self.end_headers()

    return Manejador


if __name__ == "__main__":
    sys.exit(main())
