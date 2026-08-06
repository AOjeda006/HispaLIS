"""Construcción de mensajes `ADT` V2.5.1.

Los campos se colocan **por índice** y no concatenando barras a ojo. Contar separadores a mano es
como se acaba con el número de episodio en `PV1-18` en vez de en `PV1-19`, y con un simulador que
prueba algo distinto de lo que dice probar.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime

SEPARADOR_DE_SEGMENTO = "\r"

#: `MSH-9-3`. Los eventos A01, A04, A08 y A13 comparten esta estructura en la tabla 0354 de V2.5.1;
#: `ADT_A08` no existe. El cruce medido está en `docs/adr/adr-0018-…`.
ESTRUCTURA_ADT = "ADT_A01"

#: `MSH-9-3` del `OML^O21`. Está en las dos fuentes de V2.5.1 y sin cambios desde V2.5.
ESTRUCTURA_OML = "OML_O21"

#: Los valores de la tabla 0211 que el motor acepta.
CHARSET_LATIN1 = "8859/1"
CHARSET_UTF8 = "UNICODE UTF-8"

#: Cómo se nombra en v2 (tabla 0396) el catálogo local del laboratorio, y el común.
CATALOGO_LOCAL = "99HISPALIS"
CATALOGO_LOINC = "LN"

#: `SPM-4`. Suero, que es el tipo de muestra más común del laboratorio.
SUERO = "119364003"


@dataclass(frozen=True)
class Paciente:
    """La demografía que viaja en el `PID`.

    Los apellidos van **completos y sin trocear** en `apellidos`: «de la Torre Gómez» son dos
    apellidos, y partirlos por el espacio es confundir pacientes.
    """

    nhc: str
    apellidos: str
    nombre: str
    segundo_nombre: str = ""
    sexo: str = "F"
    nacimiento: str = "19810314"
    dni: str = ""
    nuhsa: str = ""


def adt(
    *,
    evento: str,
    control_id: str,
    paciente: Paciente,
    charset: str = CHARSET_LATIN1,
    episodio: str = "",
    emisor: str = "HIS_VIRGEN",
    instalacion: str = "H_VIRGEN_MACARENA",
    momento: datetime | None = None,
) -> str:
    """Devuelve un `ADT^A01` o `ADT^A08` completo.

    Args:
        evento: `A01` (admisión) o `A08` (corrección de filiación).
        control_id: `MSH-10`. Repetirlo a propósito es cómo se prueba la deduplicación.
        paciente: la demografía.
        charset: lo que se declara en `MSH-18`; el mensaje se codificará con él al enviarlo.
        episodio: `PV1-19`, si lo hay.
        emisor: `MSH-3`.
        instalacion: `MSH-4`.
        momento: fecha del mensaje; por defecto, ahora.

    Returns:
        El mensaje con `\\r` como separador de segmento, listo para el envío MLLP.
    """
    sello = (momento or datetime.now()).strftime("%Y%m%d%H%M%S")
    segmentos = [
        _msh("ADT", evento, ESTRUCTURA_ADT, control_id, charset, emisor, instalacion, sello),
        _campos(2, {0: "EVN", 1: evento, 2: sello}),
        _pid(paciente),
    ]
    if episodio:
        segmentos.append(_campos(19, {0: "PV1", 1: "1", 2: "O", 3: "LAB", 19: episodio}))
    return SEPARADOR_DE_SEGMENTO.join(segmentos)


def oml(
    *,
    control_id: str,
    paciente: Paciente,
    volante: str,
    numero_de_acceso: str,
    pruebas: Sequence[str],
    tipo_de_muestra: str = SUERO,
    catalogo: str = CATALOGO_LOCAL,
    peticionario: str = "COL12345^Ruiz Pérez^Carmen",
    charset: str = CHARSET_LATIN1,
    emisor: str = "HIS_VIRGEN",
    instalacion: str = "H_VIRGEN_MACARENA",
    momento: datetime | None = None,
) -> str:
    """Devuelve un `OML^O21`: la petición analítica que el HIS manda al laboratorio.

    Cada prueba va en su propio grupo `ORDER` (`ORC` + `OBR` + `SPM`), que es lo que dice la
    estructura `OML_O21`. Las tres comparten `ORC-4`, que es el número de volante.

    Args:
        control_id: `MSH-10`.
        paciente: la demografía; solo se manda `PID-3` y `PID-5`.
        volante: `ORC-4`, el número que agrupa las líneas.
        numero_de_acceso: `SPM-2`, el código de la etiqueta del tubo.
        pruebas: los códigos que se piden.
        tipo_de_muestra: `SPM-4`, código SNOMED.
        catalogo: en qué codificación van los códigos de `OBR-4` (`99HISPALIS` o `LN`).
        peticionario: `ORC-12`.
        charset: `MSH-18`.
        emisor: `MSH-3`.
        instalacion: `MSH-4`.
        momento: fecha del mensaje; por defecto, ahora.

    Returns:
        El mensaje con `\\r` como separador de segmento.
    """
    sello = (momento or datetime.now()).strftime("%Y%m%d%H%M%S")
    segmentos = [
        _msh("OML", "O21", ESTRUCTURA_OML, control_id, charset, emisor, instalacion, sello),
        _pid(paciente),
    ]
    for posicion, prueba in enumerate(pruebas, start=1):
        segmentos.append(
            _campos(
                12,
                {
                    0: "ORC",
                    1: "NW",
                    2: f"P{posicion}",
                    4: volante,
                    9: sello,
                    12: peticionario,
                },
            )
        )
        segmentos.append(
            _campos(7, {0: "OBR", 1: str(posicion), 4: f"{prueba}^^{catalogo}", 7: sello})
        )
        segmentos.append(
            _campos(
                4,
                {
                    0: "SPM",
                    1: str(posicion),
                    # Los dos componentes de `SPM-2`: el que puso el peticionario y el del
                    # laboratorio. Aquí el HIS aún no sabe el nuestro, así que manda el suyo dos
                    # veces — es lo que hace un HIS que imprime él la etiqueta.
                    2: f"{numero_de_acceso}^{numero_de_acceso}",
                    4: f"{tipo_de_muestra}^^SCT",
                },
            )
        )
    return SEPARADOR_DE_SEGMENTO.join(segmentos)


def _msh(
    tipo: str,
    evento: str,
    estructura: str,
    control_id: str,
    charset: str,
    emisor: str,
    instalacion: str,
    sello: str,
) -> str:
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
            8: f"{tipo}^{evento}^{estructura}",
            9: control_id,
            10: "P",
            11: "2.5.1",
            16: "ES",
            17: charset,
        },
    )


def _pid(paciente: Paciente) -> str:
    """`PID-3` discrimina cada identificador por su tipo de la tabla 0203, no por su posición."""
    identificadores = [f"{paciente.nhc}^^^HISPALIS^MR"]
    if paciente.dni:
        identificadores.append(f"{paciente.dni}^^^MJU^NI")
    if paciente.nuhsa:
        identificadores.append(f"{paciente.nuhsa}^^^SAS^JHN")

    nombre = f"{paciente.apellidos}^{paciente.nombre}"
    if paciente.segundo_nombre:
        nombre += f"^{paciente.segundo_nombre}"

    return _campos(
        8,
        {
            0: "PID",
            1: "1",
            3: "~".join(identificadores),
            5: nombre,
            7: paciente.nacimiento,
            8: paciente.sexo,
        },
    )


def _campos(ultimo_indice: int, valores: dict[int, str]) -> str:
    """Compone un segmento colocando cada valor en su índice y dejando el resto vacío."""
    campos = [""] * (ultimo_indice + 1)
    for indice, valor in valores.items():
        campos[indice] = valor
    return "|".join(campos)
