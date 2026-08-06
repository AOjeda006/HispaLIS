"""Punto de entrada del simulador del HIS: `python -m his --evento A01 --nhc 70000001`."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence

from his.mensajes import CHARSET_LATIN1, CHARSET_UTF8, Paciente, adt
from his.mllp import CharsetNoSoportadoError, Destino, enviar

#: Los apellidos que rompen tuberías v2 españolas, y por eso son los de por defecto.
APELLIDOS_POR_DEFECTO = "MUÑOZ DE LA TORRE"

PUERTO_POR_DEFECTO = 2575


def construir_analizador() -> argparse.ArgumentParser:
    """Define la interfaz de línea de órdenes del simulador."""
    analizador = argparse.ArgumentParser(
        prog="his",
        description="Simula el HIS de la clínica: emite ADT^A01 y ADT^A08 por MLLP hacia el motor.",
    )
    analizador.add_argument("--servidor", default="localhost", help="Dónde escucha el motor.")
    analizador.add_argument("--puerto", type=int, default=PUERTO_POR_DEFECTO)
    analizador.add_argument(
        "--sin-tls",
        action="store_true",
        help="Habla en claro. Solo para depurar en local: el plano de sistemas va cifrado (D4).",
    )
    analizador.add_argument(
        "--verificar-certificado",
        action="store_true",
        help="Exige certificado válido. Apagado por defecto: en desarrollo es autofirmado.",
    )
    analizador.add_argument("--evento", choices=["A01", "A08"], default="A01")
    analizador.add_argument("--nhc", default="70000001", help="Ocho dígitos.")
    analizador.add_argument("--apellidos", default=APELLIDOS_POR_DEFECTO)
    analizador.add_argument("--nombre", default="Begoña")
    analizador.add_argument("--segundo-nombre", default="María")
    analizador.add_argument("--sexo", choices=["F", "M", "O", "U"], default="F")
    analizador.add_argument("--dni", default="12345678Z")
    analizador.add_argument("--nuhsa", default="AN0123456789", help="Vacío para no mandarlo.")
    analizador.add_argument("--episodio", default="EP20260806001")
    analizador.add_argument(
        "--charset",
        choices=[CHARSET_LATIN1, CHARSET_UTF8],
        default=CHARSET_LATIN1,
        help="Lo que se declara en MSH-18 y con lo que se codifica el cable.",
    )
    analizador.add_argument(
        "--control-id",
        default=None,
        help="MSH-10. Repetir el mismo a propósito es cómo se prueba la deduplicación del motor.",
    )
    analizador.add_argument(
        "--repetir",
        type=int,
        default=1,
        help="Cuántas veces se manda el MISMO mensaje, con el mismo MSH-10.",
    )
    return analizador


def main(argumentos: Sequence[str] | None = None) -> int:
    """Manda el mensaje y escribe el acuse por la salida estándar."""
    opciones = construir_analizador().parse_args(argumentos)

    paciente = Paciente(
        nhc=opciones.nhc,
        apellidos=opciones.apellidos,
        nombre=opciones.nombre,
        segundo_nombre=opciones.segundo_nombre,
        sexo=opciones.sexo,
        dni=opciones.dni,
        nuhsa=opciones.nuhsa,
    )
    control_id = opciones.control_id or f"HIS{opciones.nhc}{opciones.evento}"
    mensaje = adt(
        evento=opciones.evento,
        control_id=control_id,
        paciente=paciente,
        charset=opciones.charset,
        episodio=opciones.episodio,
    )
    destino = Destino(
        servidor=opciones.servidor,
        puerto=opciones.puerto,
        tls=not opciones.sin_tls,
        verificar_certificado=opciones.verificar_certificado,
    )

    try:
        for intento in range(1, opciones.repetir + 1):
            acuse = enviar(mensaje, destino)
            print(f"--- envío {intento} de {opciones.repetir} · MSH-10 = {control_id}")
            print(acuse.replace("\r", "\n"))
    except (CharsetNoSoportadoError, OSError) as fallo:
        print(f"No se pudo entregar el mensaje: {fallo}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
