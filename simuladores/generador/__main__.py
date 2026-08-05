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
from generador.escenario import generar
from generador.salida import escribir
from generador.terminologia import TerminologiaNoDisponibleError, cargar_catalogo


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
    analizador.add_argument(
        "--fecha",
        default=None,
        help=(
            "Día de referencia en ISO (2026-08-05). La actividad se reparte hacia atrás desde "
            "aquí, así que fijarlo es lo que hace la salida reproducible entre días distintos."
        ),
    )
    analizador.add_argument(
        "--terminologia",
        default=None,
        help=(
            "Directorio con los recursos que produce SUSHI. Por defecto, "
            "«ig/fsh-generated/resources» del propio repositorio."
        ),
    )
    return analizador


def resolver_configuracion(argumentos: Sequence[str] | None = None) -> ConfiguracionGeneracion:
    """Traduce los argumentos de línea de órdenes a una configuración validada."""
    opciones = construir_analizador().parse_args(argumentos)
    return crear_configuracion(
        semilla=opciones.semilla,
        pacientes=opciones.pacientes,
        salida=opciones.salida,
        fecha=opciones.fecha,
    )


def main(argumentos: Sequence[str] | None = None) -> int:
    """Ejecuta el generador y devuelve el código de salida del proceso."""
    opciones = construir_analizador().parse_args(argumentos)
    try:
        configuracion = crear_configuracion(
            semilla=opciones.semilla,
            pacientes=opciones.pacientes,
            salida=opciones.salida,
            fecha=opciones.fecha,
        )
        catalogo = cargar_catalogo(opciones.terminologia)
    except ConfiguracionInvalidaError as error:
        print(f"Configuración inválida: {error}", file=sys.stderr)
        return 2
    except TerminologiaNoDisponibleError as error:
        # No se genera con un catálogo a medias ni con uno inventado: mejor no generar nada.
        print(f"Falta la terminología de la guía: {error}", file=sys.stderr)
        return 3

    corpus = generar(configuracion, catalogo)
    destino = escribir(corpus, configuracion)

    print(
        f"Generados {len(corpus.recursos)} recursos de {configuracion.pacientes} pacientes "
        f"—{corpus.episodios} episodios, {corpus.muestras_rechazadas} muestras rechazadas, "
        f"{corpus.reflejas} pruebas reflejas— con semilla {configuracion.semilla} en {destino}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
