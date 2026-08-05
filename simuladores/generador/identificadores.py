"""Identificadores españoles sintéticos, con los dígitos de control que de verdad les tocan.

Un DNI cuya letra no cuadra es un dato que *parece* bueno y no lo es: pasa el ojo, pasa el perfil
—D16 prohíbe validar el formato de los identificadores que el laboratorio no emite— y revienta el
día que alguien lo valide de verdad, en producción y con prisa. Generarlos bien cuesta veinte
líneas.

Lo que se imita y lo que no:

* **DNI y NIE** llevan su letra calculada, porque el algoritmo es público y verificable.
* **NUHSA** lleva su formato real (`AN` + 10 dígitos), que también lo es.
* **CIP-SNS** sale como dieciséis caracteres alfanuméricos **opacos**. El diseño (§4.1) da la
  longitud pero anota que la estructura interna del Anexo I del RD 183/2004 **no está contrastada**.
  Inventarse una estructura plausible sería peor que no imitarla: produciría datos con apariencia de
  ser correctos que nadie ha comprobado contra la norma.
* **NHC** son ocho dígitos porque el invariante `hlis-nhc-1` del perfil lo exige — es el único
  identificador que el laboratorio emite y el único con formato validado.
"""

from __future__ import annotations

import random
import string

#: Letras del DNI en el orden que fija el resto de dividir el número entre 23.
LETRAS_DNI = "TRWAGMYFPDXBNJZSQVHLCKE"

#: Letra inicial de un NIE y el dígito por el que se sustituye para calcular el control.
PREFIJOS_NIE = {"X": "0", "Y": "1", "Z": "2"}

PREFIJO_NUHSA = "AN"
DIGITOS_NUHSA = 10
LONGITUD_CIP_SNS = 16
DIGITOS_NHC = 8


def letra_de_control(numero: int) -> str:
    """Devuelve la letra que corresponde a un número de documento español."""
    return LETRAS_DNI[numero % len(LETRAS_DNI)]


def dni(azar: random.Random) -> str:
    """Genera un DNI sintético con su letra correcta."""
    numero = azar.randrange(10**7, 10**8)
    return f"{numero:08d}{letra_de_control(numero)}"


def nie(azar: random.Random) -> str:
    """Genera un NIE sintético con su letra correcta.

    El control se calcula sustituyendo la letra inicial por su dígito: `X`→0, `Y`→1, `Z`→2. Es un
    detalle fácil de pasar por alto, y saltárselo produce NIE con letra incorrecta en dos tercios de
    los casos —los que no empiezan por `X`—, que es justo la parte del corpus que menos se mira.
    """
    inicial = azar.choice(list(PREFIJOS_NIE))
    numero = azar.randrange(10**6, 10**7)
    equivalente = int(f"{PREFIJOS_NIE[inicial]}{numero:07d}")
    return f"{inicial}{numero:07d}{letra_de_control(equivalente)}"


def dni_o_nie(azar: random.Random, proporcion_extranjeros: float = 0.12) -> str:
    """Genera un documento de identidad, con una minoría de NIE.

    Args:
        azar: Fuente de aleatoriedad ya sembrada.
        proporcion_extranjeros: Fracción de pacientes con NIE en vez de DNI. Sevilla tiene población
            extranjera, y un corpus que solo trae DNI nunca prueba el camino del NIE.
    """
    return nie(azar) if azar.random() < proporcion_extranjeros else dni(azar)


def es_documento_valido(documento: str) -> bool:
    """Comprueba la letra de control de un DNI o un NIE.

    Existe para que lo use el test: un generador que se valida a sí mismo con su propio algoritmo no
    prueba nada, pero esta función comprueba la *cadena ya escrita*, que es lo que verá el sistema.
    """
    if len(documento) != 9:
        return False

    cuerpo, letra = documento[:-1], documento[-1]
    if cuerpo[0] in PREFIJOS_NIE:
        cuerpo = PREFIJOS_NIE[cuerpo[0]] + cuerpo[1:]
    if not cuerpo.isdigit():
        return False

    return letra == letra_de_control(int(cuerpo))


def nuhsa(azar: random.Random) -> str:
    """Genera un NUHSA sintético: `AN` más diez dígitos."""
    return PREFIJO_NUHSA + "".join(azar.choice(string.digits) for _ in range(DIGITOS_NUHSA))


def cip_sns(azar: random.Random) -> str:
    """Genera un CIP-SNS sintético: dieciséis caracteres alfanuméricos opacos."""
    alfabeto = string.ascii_uppercase + string.digits
    return "".join(azar.choice(alfabeto) for _ in range(LONGITUD_CIP_SNS))


def nhc(orden: int) -> str:
    """Devuelve el número de historia clínica del paciente que hace `orden` en esta ejecución.

    Es correlativo y no aleatorio a propósito: el NHC lo emite el laboratorio, y un laboratorio los
    asigna en orden. Como efecto colateral, dos ejecuciones con la misma semilla producen los mismos
    NHC sin depender del número de veces que se haya llamado al azar antes.
    """
    if not 0 <= orden < 10**DIGITOS_NHC:
        raise ValueError(f"El NHC son {DIGITOS_NHC} dígitos y el orden {orden} no cabe.")
    return f"{orden:0{DIGITOS_NHC}d}"


def numero_de_peticion(anio: int, orden: int) -> str:
    """Devuelve el número de petición, que agrupa todas las líneas de un mismo volante."""
    return f"P-{anio}-{orden:06d}"


def numero_de_acceso(anio: int, orden: int) -> str:
    """Devuelve el número de acceso de una muestra: el código que va en la etiqueta del tubo."""
    return f"{anio % 100:02d}-{orden:07d}"
