"""Construcción de mensajes `ORU^R01` V2.5.1.

Los campos se colocan **por índice**, como en el simulador del HIS y por la misma razón: contar
barras a ojo es como se acaba con el estado del resultado en `OBX-10` en vez de en `OBX-11`.

**El analizador informa en LOINC por defecto**, no en el dialecto del laboratorio. Es lo realista
—un analizador comercial no conoce el catálogo de este laboratorio— y es lo que obliga al motor a
traducir con el `ConceptMap` de la guía en vez de con una tabla escrita a mano. Se puede cambiar
con `catalogo` para probar el otro camino.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime

SEPARADOR_DE_SEGMENTO = "\r"

#: `MSH-9-3`. En las dos fuentes de V2.5.1 y sin cambios desde V2.5 (ver `docs/adr/adr-0018-…`).
ESTRUCTURA_ORU = "ORU_R01"

CHARSET_LATIN1 = "8859/1"
CHARSET_UTF8 = "UNICODE UTF-8"

#: Cómo se nombra cada catálogo en v2 (tabla 0396).
CATALOGO_LOINC = "LN"
CATALOGO_LOCAL = "99HISPALIS"

#: Y cómo se nombra el vocabulario de los VALORES cualitativos, que es otro distinto: `99HISPALIS`
#: nombra las pruebas y este los resultados. Van en campos distintos del mismo `OBX` —`OBX-3` y
#: `OBX-5`— y confundirlos guarda un resultado como si fuera una prueba.
CATALOGO_CUALITATIVO = "99HISPCUAL"

#: `SPM-4`: suero.
SUERO = "119364003"


@dataclass(frozen=True)
class Medida:
    """Una cifra que el analizador ha producido.

    Attributes:
        codigo: el código de la prueba, en el catálogo que declare el mensaje.
        valor: el valor, como cadena — el analizador no sabe de tipos de Python.
        unidad: `OBX-6`. Vacío si la prueba es cualitativa.
        tipo: `OBX-2`. `NM` numérico, `ST` texto.
        estado: `OBX-11`. `F` es «final del analizador», que NO es «validado».
    """

    codigo: str
    valor: str
    unidad: str = ""
    tipo: str = "NM"
    estado: str = "F"


@dataclass(frozen=True)
class Paciente:
    """Lo mínimo que el analizador sabe del paciente: su NHC y su nombre."""

    nhc: str
    apellidos: str
    nombre: str


def oru(
    *,
    control_id: str,
    paciente: Paciente,
    numero_de_acceso: str,
    medidas: Sequence[Medida],
    volante: str = "",
    catalogo: str = CATALOGO_LOINC,
    charset: str = CHARSET_LATIN1,
    emisor: str = "AU5800",
    instalacion: str = "LAB_SEVILLA",
    momento: datetime | None = None,
) -> str:
    """Devuelve un `ORU^R01` con un `OBX` por medida.

    Args:
        control_id: `MSH-10`. Repetirlo a propósito es cómo se prueba la deduplicación del motor.
        paciente: a quién pertenece la muestra.
        numero_de_acceso: `SPM-2`, la etiqueta que el analizador leyó del tubo.
        medidas: lo que midió.
        volante: `ORC-4`, si el analizador lo conoce. Vacío si no.
        catalogo: en qué codificación van los códigos de `OBX-3`.
        charset: `MSH-18`.
        emisor: `MSH-3`; el modelo del analizador.
        instalacion: `MSH-4`.
        momento: cuándo se midió; por defecto, ahora.

    Returns:
        El mensaje con `\\r` como separador de segmento.
    """
    sello = (momento or datetime.now()).strftime("%Y%m%d%H%M%S")
    segmentos = [
        _msh(control_id, charset, emisor, instalacion, sello),
        _pid(paciente),
        _campos(4, {0: "ORC", 1: "RE", 2: numero_de_acceso, 4: volante}),
        _campos(
            7,
            {
                0: "OBR",
                1: "1",
                3: numero_de_acceso,
                4: f"PANEL^^{catalogo}",
                7: sello,
            },
        ),
    ]
    for posicion, medida in enumerate(medidas, start=1):
        segmentos.append(_obx(posicion, medida, catalogo, sello, emisor))
    segmentos.append(
        _campos(
            4,
            {
                0: "SPM",
                1: "1",
                2: f"{numero_de_acceso}^{numero_de_acceso}",
                4: f"{SUERO}^^SCT",
            },
        )
    )
    return SEPARADOR_DE_SEGMENTO.join(segmentos)


def _msh(control_id: str, charset: str, emisor: str, instalacion: str, sello: str) -> str:
    return _campos(
        17,
        {
            0: "MSH",
            1: "^~\\&",
            2: emisor,
            3: instalacion,
            4: "HISPALIS",
            5: "LAB_SEVILLA",
            6: sello,
            8: f"ORU^R01^{ESTRUCTURA_ORU}",
            9: control_id,
            10: "P",
            11: "2.5.1",
            16: "ES",
            17: charset,
        },
    )


def _pid(paciente: Paciente) -> str:
    """El NHC va con su tipo `MR` de la tabla 0203: es por lo que el motor busca al paciente."""
    return _campos(
        5,
        {
            0: "PID",
            1: "1",
            3: f"{paciente.nhc}^^^HISPALIS^MR",
            5: f"{paciente.apellidos}^{paciente.nombre}",
        },
    )


def _obx(posicion: int, medida: Medida, catalogo: str, sello: str, analizador: str) -> str:
    """El aparato va en `OBX-18`, no en `OBX-16`.

    `OBX-16` es el *responsible observer*, o sea una **persona**; el identificador del equipo es
    `OBX-18`. Colocar ahí el modelo del analizador es lo que hace que al otro lado alguien lo tome
    por un profesional y lo escriba donde no cabe.
    """
    return _campos(
        18,
        {
            0: "OBX",
            1: str(posicion),
            2: medida.tipo,
            3: f"{medida.codigo}^^{catalogo}",
            5: medida.valor,
            6: medida.unidad,
            11: medida.estado,
            14: sello,
            18: analizador,
        },
    )


def _campos(ultimo_indice: int, valores: dict[int, str]) -> str:
    """Compone un segmento colocando cada valor en su índice y dejando el resto vacío."""
    campos = [""] * (ultimo_indice + 1)
    for indice, valor in valores.items():
        campos[indice] = valor
    return "|".join(campos)
