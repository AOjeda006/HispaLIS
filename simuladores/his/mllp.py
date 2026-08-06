"""Envío MLLP sobre TLS.

Aquí sí se escribe el *framing* a mano, y conviene decir por qué no contradice la regla del
proyecto: la regla —«el framing lo implementa la librería»— vale para el **motor**, que es código de
producción y usa HAPI. Este es un simulador en Python, y en Python no hay una librería MLLP que
merezca una dependencia para tres bytes de sobre.

Lo que sí hay que saber: el documento normativo de MLLP es un estándar de **HL7 V3** y está
**retirado desde mayo de 2025 sin sustituto designado**; el apéndice B de V2.5.1 está vacío. Los
tres bytes de abajo están tomados de lo que implementa HAPI, que es hoy la referencia de facto — no
de un documento que se pueda citar.
"""

from __future__ import annotations

import socket
import ssl
from dataclasses import dataclass

#: Los bytes de sobre: `<SB>` al principio, `<EB><CR>` al final.
INICIO_DE_BLOQUE = b"\x0b"
FIN_DE_BLOQUE = b"\x1c"
RETORNO = b"\x0d"

#: Los juegos de la tabla 0211 que el motor acepta, con su nombre en Python.
CODIFICACIONES = {
    "ASCII": "ascii",
    "8859/1": "iso-8859-1",
    "8859/15": "iso-8859-15",
    "UNICODE": "utf-8",
    "UNICODE UTF-8": "utf-8",
}


class CharsetNoSoportadoError(ValueError):
    """El mensaje declara un `MSH-18` que este simulador no sabe codificar."""


@dataclass(frozen=True)
class Destino:
    """A dónde se manda.

    `verificar_certificado` se puede apagar para el entorno de desarrollo, donde el motor
    presenta un autofirmado. En cualquier otro sitio se deja encendido: un cliente que no verifica
    es un canal cifrado contra quien sea.
    """

    servidor: str
    puerto: int
    tls: bool = True
    verificar_certificado: bool = False
    tiempo_de_espera: float = 10.0


def enviar(mensaje: str, destino: Destino) -> str:
    """Manda el mensaje y devuelve el acuse.

    El mensaje se codifica con el juego que él mismo declara en `MSH-18`. Es lo que hace realista al
    simulador: si declarase una cosa y mandase otra, estaría probando el camino de error en vez del
    normal.

    Args:
        mensaje: el mensaje HL7 v2, con `\\r` de separador de segmento.
        destino: dónde escucha el motor.

    Returns:
        El acuse decodificado con el juego que declara él mismo.

    Raises:
        CharsetNoSoportadoError: si `MSH-18` trae algo que no sabemos codificar.
    """
    codificacion = _codificacion_de(mensaje)
    sobre = INICIO_DE_BLOQUE + mensaje.encode(codificacion) + FIN_DE_BLOQUE + RETORNO

    with _abrir(destino) as conexion:
        conexion.sendall(sobre)
        respuesta = _leer_hasta_fin_de_bloque(conexion)

    return _decodificar_acuse(respuesta)


def _abrir(destino: Destino) -> socket.socket:
    conexion = socket.create_connection(
        (destino.servidor, destino.puerto), timeout=destino.tiempo_de_espera
    )
    if not destino.tls:
        return conexion

    contexto = ssl.create_default_context()
    if not destino.verificar_certificado:
        contexto.check_hostname = False
        contexto.verify_mode = ssl.CERT_NONE
    return contexto.wrap_socket(conexion, server_hostname=destino.servidor)


def _leer_hasta_fin_de_bloque(conexion: socket.socket) -> bytes:
    """Lee hasta `<EB><CR>`.

    Un mensaje MLLP no acaba cuando el `recv` devuelve poco: acaba con su marca de fin. Cortar por
    tamaño es cómo se leen acuses partidos por la mitad en cuanto el mensaje crece.
    """
    recibido = b""
    while not recibido.endswith(FIN_DE_BLOQUE + RETORNO):
        trozo = conexion.recv(4096)
        if not trozo:
            raise ConnectionError("El motor cerró la conexión sin acusar recibo")
        recibido += trozo
    return recibido.removeprefix(INICIO_DE_BLOQUE).removesuffix(FIN_DE_BLOQUE + RETORNO)


def _decodificar_acuse(crudo: bytes) -> str:
    """El acuse también declara su charset, así que se lee dos veces: para saberlo y para leerlo."""
    tentativa = crudo.decode("iso-8859-1", errors="replace")
    return crudo.decode(_codificacion_de(tentativa), errors="replace")


def _codificacion_de(mensaje: str) -> str:
    declarado = _msh_18(mensaje)
    if not declarado:
        # `MSH-18` vacío es legal y significa «lo acordado entre las partes». Aquí, latín-1: es lo
        # que manda un HIS español que no lo declara. Ver `CharsetDeclarado` en el motor.
        return "iso-8859-1"
    codificacion = CODIFICACIONES.get(declarado.upper())
    if codificacion is None:
        raise CharsetNoSoportadoError(
            f"MSH-18 declara «{declarado}» y este simulador solo sabe codificar "
            f"{', '.join(CODIFICACIONES)}"
        )
    return codificacion


def _msh_18(mensaje: str) -> str:
    cabecera = mensaje.split("\r", 1)[0]
    campos = cabecera.split("|")
    # El índice 17 de la lista es `MSH-18`: el separador de campo es `MSH-1` y no ocupa hueco.
    return campos[17].strip() if len(campos) > 17 else ""
