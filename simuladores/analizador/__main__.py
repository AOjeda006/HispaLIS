"""Punto de entrada del simulador del analizador: `python -m analizador --acceso ACC1`."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence

from analizador.mensajes import (
    CATALOGO_CUALITATIVO,
    CATALOGO_LOCAL,
    CATALOGO_LOINC,
    CHARSET_LATIN1,
    CHARSET_UTF8,
    Medida,
    Paciente,
    oru,
)
from mllp import CharsetNoSoportadoError, Destino, enviar

PUERTO_POR_DEFECTO = 2575

#: Un panel de bioquímica en LOINC, con cifras dentro de rango. Es lo que emite por defecto.
PANEL_POR_DEFECTO = "2345-7:92:mg/dL,2160-0:0.9:mg/dL,2823-3:4.2:mmol/L"


def construir_analizador() -> argparse.ArgumentParser:
    """Define la interfaz de línea de órdenes del simulador."""
    analizador = argparse.ArgumentParser(
        prog="analizador",
        description="Simula el analizador del laboratorio: emite ORU^R01 por MLLP hacia el motor.",
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
    analizador.add_argument("--nhc", default="70000001", help="Ocho dígitos.")
    analizador.add_argument("--apellidos", default="MUÑOZ DE LA TORRE")
    analizador.add_argument("--nombre", default="Begoña")
    analizador.add_argument("--acceso", default="ACC70000001", help="SPM-2, la etiqueta del tubo.")
    analizador.add_argument("--volante", default="", help="ORC-4, si el analizador lo conoce.")
    analizador.add_argument(
        "--catalogo",
        choices=[CATALOGO_LOINC, CATALOGO_LOCAL],
        default=CATALOGO_LOINC,
        help="En qué codificación van los códigos de OBX-3. LOINC por defecto: es lo realista.",
    )
    analizador.add_argument(
        "--medidas",
        default=PANEL_POR_DEFECTO,
        help="Lista «codigo:valor:unidad» separada por comas. Unidad vacía = resultado de texto.",
    )
    analizador.add_argument(
        "--charset", choices=[CHARSET_LATIN1, CHARSET_UTF8], default=CHARSET_LATIN1
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


def medidas_de(especificacion: str) -> list[Medida]:
    """Traduce `codigo:valor:unidad,…` a medidas.

    Una unidad vacía significa resultado cualitativo, y entonces hay dos formas de informarlo que
    **no** son la misma:

    - `LEGIOAG:Negativo` — una frase. `OBX-2` va como `ST` y lo que llega al laboratorio es una
      descripción. Es lo que manda un analizador que no comparte vocabulario con nadie.
    - `LEGIOAG:POS^Positivo` — un **concepto**. `OBX-2` va como `CWE` y el valor lleva sus
      componentes: código, cómo se lee y de qué vocabulario sale. El tercero lo pone este simulador
      —`CATALOGO_CUALITATIVO`—, porque es el dialecto en el que está configurado *este* analizador.

    La diferencia se decide por el `^`, que es el separador de componente de v2: no hay ninguna
    lista de códigos dentro del simulador, y no puede haberla. De cuál de las dos formas llegue
    depende que una Legionella positiva se declare: la regla del laboratorio compara **códigos**, y
    una frase no lo es por mucho que ponga «Positivo».
    """
    medidas = []
    for trozo in especificacion.split(","):
        partes = trozo.split(":")
        codigo = partes[0].strip()
        valor = partes[1].strip() if len(partes) > 1 else ""
        unidad = partes[2].strip() if len(partes) > 2 else ""
        if unidad:
            medidas.append(Medida(codigo=codigo, valor=valor, unidad=unidad, tipo="NM"))
        elif "^" in valor:
            componentes = valor.split("^")
            if len(componentes) < 3:
                componentes += [""] * (3 - len(componentes))
                componentes[2] = CATALOGO_CUALITATIVO
            medidas.append(Medida(codigo=codigo, valor="^".join(componentes), tipo="CWE"))
        else:
            medidas.append(Medida(codigo=codigo, valor=valor, tipo="ST"))
    return medidas


def main(argumentos: Sequence[str] | None = None) -> int:
    """Manda el mensaje y escribe el acuse por la salida estándar."""
    opciones = construir_analizador().parse_args(argumentos)

    control_id = opciones.control_id or f"AN{opciones.acceso}"
    mensaje = oru(
        control_id=control_id,
        paciente=Paciente(nhc=opciones.nhc, apellidos=opciones.apellidos, nombre=opciones.nombre),
        numero_de_acceso=opciones.acceso,
        medidas=medidas_de(opciones.medidas),
        volante=opciones.volante,
        catalogo=opciones.catalogo,
        charset=opciones.charset,
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
