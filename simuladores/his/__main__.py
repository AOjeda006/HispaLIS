"""Punto de entrada del simulador del HIS: `python -m his --evento A01 --nhc 70000001`."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence

from his.mensajes import (
    CATALOGO_LOCAL,
    CATALOGO_LOINC,
    CHARSET_LATIN1,
    CHARSET_UTF8,
    SUERO,
    Paciente,
    adt,
    oml,
)
from mllp import CharsetNoSoportadoError, Destino, Escucha, ServidorDelHis, enviar

#: Los apellidos que rompen tuberías v2 españolas, y por eso son los de por defecto.
APELLIDOS_POR_DEFECTO = "MUÑOZ DE LA TORRE"

PUERTO_POR_DEFECTO = 2575

#: Donde el HIS espera el `ORU^R01` que el laboratorio le devuelve al validar el informe.
PUERTO_DE_ESCUCHA_POR_DEFECTO = 2576

#: Un perfil de bioquímica del catálogo local. Es lo que pide por defecto el `OML^O21`.
PRUEBAS_POR_DEFECTO = "GLU,CREA,K"


def construir_analizador() -> argparse.ArgumentParser:
    """Define la interfaz de línea de órdenes del simulador."""
    analizador = argparse.ArgumentParser(
        prog="his",
        description=(
            "Simula el HIS de la clínica: emite ADT^A01, ADT^A08 y OML^O21 por MLLP hacia el motor."
        ),
    )
    analizador.add_argument(
        "--mensaje",
        choices=["adt", "oml"],
        default="adt",
        help="Qué se manda: la filiación del paciente (adt) o la petición analítica (oml).",
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
    analizador.add_argument(
        "--evento", choices=["A01", "A08"], default="A01", help="Solo para `--mensaje adt`."
    )
    analizador.add_argument("--nhc", default="70000001", help="Ocho dígitos.")
    analizador.add_argument("--apellidos", default=APELLIDOS_POR_DEFECTO)
    analizador.add_argument("--nombre", default="Begoña")
    analizador.add_argument("--segundo-nombre", default="María")
    analizador.add_argument("--sexo", choices=["F", "M", "O", "U"], default="F")
    analizador.add_argument("--dni", default="12345678Z")
    analizador.add_argument("--nuhsa", default="AN0123456789", help="Vacío para no mandarlo.")
    analizador.add_argument("--episodio", default="EP20260806001")
    analizador.add_argument(
        "--volante",
        default="VOL20260806001",
        help="ORC-4 del OML: el número que agrupa las líneas.",
    )
    analizador.add_argument(
        "--acceso", default="ACC70000001", help="SPM-2 del OML: el código de la etiqueta del tubo."
    )
    analizador.add_argument(
        "--pruebas",
        default=PRUEBAS_POR_DEFECTO,
        help="Códigos separados por comas. Una línea de petición por cada uno.",
    )
    analizador.add_argument(
        "--catalogo",
        choices=[CATALOGO_LOCAL, CATALOGO_LOINC],
        default=CATALOGO_LOCAL,
        help="En qué codificación van los códigos de OBR-4. El HIS pide en el dialecto del "
        "laboratorio; con LN se prueba el camino contrario.",
    )
    analizador.add_argument("--tipo-muestra", default=SUERO, help="SPM-4, código SNOMED CT.")
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
    analizador.add_argument(
        "--escuchar",
        action="store_true",
        help="En vez de mandar, espera el ORU^R01 que el laboratorio devuelve al validar.",
    )
    analizador.add_argument("--puerto-escucha", type=int, default=PUERTO_DE_ESCUCHA_POR_DEFECTO)
    analizador.add_argument(
        "--acuse",
        choices=["AA", "AE", "AR"],
        default="AA",
        help="Qué contesta el HIS en MSA-1. Con AE se prueba qué hace el laboratorio si rechazan.",
    )
    analizador.add_argument(
        "--cuantos", type=int, default=1, help="Cuántos mensajes se esperan antes de terminar."
    )
    analizador.add_argument(
        "--espera", type=float, default=60.0, help="Segundos que se aguanta escuchando."
    )
    return analizador


def escuchar(opciones: argparse.Namespace) -> int:
    """Espera lo que el laboratorio devuelve y lo escribe por la salida estándar.

    Se queda en claro a propósito cuando no se le da certificado: el plano de sistemas va cifrado
    (D4), pero un HIS de juguete no tiene PKI, y exigirle una haría imposible la prueba manual.
    """
    with ServidorDelHis(
        Escucha(puerto=opciones.puerto_escucha), codigo_de_acuse=opciones.acuse
    ) as servidor:
        print(f"HIS escuchando en el puerto {servidor.puerto}; acusa {opciones.acuse}.")
        try:
            recibidos = servidor.esperar(opciones.cuantos, tiempo_max=opciones.espera)
        except TimeoutError as fallo:
            print(f"No llegó nada: {fallo}", file=sys.stderr)
            return 1
        for numero, mensaje in enumerate(recibidos, start=1):
            print(f"--- recibido {numero} de {len(recibidos)}")
            print(mensaje.replace("\r", "\n"))
    return 0


def componer(opciones: argparse.Namespace) -> tuple[str, str]:
    """Devuelve el `MSH-10` y el mensaje que toca según `--mensaje`.

    El identificador por defecto es **determinista**: repetir la misma orden manda el mismo
    `MSH-10`, que es justo lo que ejercita la deduplicación del motor sin tener que copiarlo a mano.
    """
    paciente = Paciente(
        nhc=opciones.nhc,
        apellidos=opciones.apellidos,
        nombre=opciones.nombre,
        segundo_nombre=opciones.segundo_nombre,
        sexo=opciones.sexo,
        dni=opciones.dni,
        nuhsa=opciones.nuhsa,
    )
    if opciones.mensaje == "oml":
        control_id = opciones.control_id or f"HIS{opciones.volante}"
        return control_id, oml(
            control_id=control_id,
            paciente=paciente,
            volante=opciones.volante,
            numero_de_acceso=opciones.acceso,
            pruebas=[codigo.strip() for codigo in opciones.pruebas.split(",") if codigo.strip()],
            tipo_de_muestra=opciones.tipo_muestra,
            catalogo=opciones.catalogo,
            charset=opciones.charset,
        )

    control_id = opciones.control_id or f"HIS{opciones.nhc}{opciones.evento}"
    return control_id, adt(
        evento=opciones.evento,
        control_id=control_id,
        paciente=paciente,
        charset=opciones.charset,
        episodio=opciones.episodio,
    )


def main(argumentos: Sequence[str] | None = None) -> int:
    """Manda el mensaje y escribe el acuse por la salida estándar."""
    opciones = construir_analizador().parse_args(argumentos)
    if opciones.escuchar:
        return escuchar(opciones)

    control_id, mensaje = componer(opciones)
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
