"""Transporte MLLP compartido por los simuladores.

Vive fuera de `his/` y de `analizador/` porque el HIS y el analizador son **dos sistemas distintos**
que hablan el mismo transporte. Que el analizador importara de `his` sería modelar mal algo que se
entiende con solo mirar el árbol de paquetes: el sobre MLLP no es del HIS.
"""

from mllp.cliente import (
    CODIFICACIONES,
    FIN_DE_BLOQUE,
    INICIO_DE_BLOQUE,
    RETORNO,
    CharsetNoSoportadoError,
    Destino,
    enviar,
)
from mllp.servidor import Escucha, ServidorDelHis, acuse_de

__all__ = [
    "CODIFICACIONES",
    "FIN_DE_BLOQUE",
    "INICIO_DE_BLOQUE",
    "RETORNO",
    "CharsetNoSoportadoError",
    "Destino",
    "Escucha",
    "ServidorDelHis",
    "acuse_de",
    "enviar",
]
