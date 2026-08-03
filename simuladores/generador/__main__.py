"""Punto de entrada del generador: `python -m generador --seed 42 --pacientes 100`."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence

from generador.configuracion import (
    PACIENTES_POR_DEFECTO,
    SEMILLA_POR_DEFECTO,
    ConfiguracionGeneracion,
    ConfiguracionInvalidaError,
    crear_configuracion,
)


def construir_analizador() -> argparse.ArgumentParser:
    """Define la interfaz de línea de órdenes del generador."""
    analizador = argparse.ArgumentParser(
        prog="generador",
        description="Genera datos sintéticos españoles para HispaLIS.",
    )
    analizador.add_argument(
        "--seed",
        dest="semilla",
        type=int,
        default=SEMILLA_POR_DEFECTO,
        help="Semilla del generador aleatorio; la misma semilla produce la misma salida.",
    )
    analizador.add_argument(
        "--pacientes",
        type=int,
        default=PACIENTES_POR_DEFECTO,
        help="Número de pacientes sintéticos a generar.",
    )
    analizador.add_argument(
        "--salida",
        default="salida-generador",
        help="Directorio donde se escriben los recursos generados.",
    )
    return analizador


def resolver_configuracion(argumentos: Sequence[str] | None = None) -> ConfiguracionGeneracion:
    """Traduce los argumentos de línea de órdenes a una configuración validada."""
    opciones = construir_analizador().parse_args(argumentos)
    return crear_configuracion(
        semilla=opciones.semilla,
        pacientes=opciones.pacientes,
        salida=opciones.salida,
    )


def main(argumentos: Sequence[str] | None = None) -> int:
    """Ejecuta el generador y devuelve el código de salida del proceso."""
    try:
        configuracion = resolver_configuracion(argumentos)
    except ConfiguracionInvalidaError as error:
        print(f"Configuración inválida: {error}", file=sys.stderr)
        return 2

    print(
        f"Generación configurada: {configuracion.pacientes} pacientes "
        f"con semilla {configuracion.semilla}, salida en {configuracion.salida}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
