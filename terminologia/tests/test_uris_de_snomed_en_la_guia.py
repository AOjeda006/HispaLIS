"""Las URI de SNOMED CT que escribe la guía, contra la forma que exige la especificación.

SNOMED tiene **una** URI de sistema, `http://snomed.info/sct`, y una familia de URI de edición y
versión que se escriben debajo. Cambiar una por otra produce URI que parecen razonables, que SUSHI
compila sin decir nada y que **ningún servidor de terminología resuelve**. Este componente es quien
carga la terminología, así que es quien tiene que notarlo — y hay que comprobarlo desde fuera del
FSH, porque un alias que todavía no usa ningún perfil no lo mira ni el validador oficial.
"""

from __future__ import annotations

import re
from pathlib import Path

FSH = Path(__file__).resolve().parents[2] / "ig" / "input" / "fsh"
ALIASES = FSH / "aliases.fsh"

#: Cualquier URI de SNOMED que pretenda ser un `ValueSet` implícito, esté bien escrita o no.
IMPLICITA = re.compile(r"http://snomed\.info/sct\S*fhir_vs\S*")

#: Las cinco formas del estándar, sobre la URI de SNOMED con edición y versión opcionales.
CORRECTA = re.compile(
    r"^http://snomed\.info/sct(/\d+(/version/\d{8})?)?\?fhir_vs(=(isa|refset|ecl)(/\S+)?)?$"
)

#: Una URI de edición —con o sin versión—, que NO es la URI del sistema.
EDICION = re.compile(r"^http://snomed\.info/sct/\d+(/version/\d{8})?$")

DECLARACION = re.compile(r"^Alias:\s+\$(\S+)\s*=\s*(\S+)\s*$", re.MULTILINE)


def _ficheros() -> list[Path]:
    return sorted(FSH.rglob("*.fsh"))


def test_las_uris_de_valueset_implicito_son_las_del_estandar() -> None:
    mal_escritas = [
        f"{fichero.relative_to(FSH)}: {uri}"
        for fichero in _ficheros()
        for uri in IMPLICITA.findall(fichero.read_text(encoding="utf-8"))
        if not CORRECTA.match(uri)
    ]

    assert not mal_escritas, (
        "Un `ValueSet` implícito de SNOMED se escribe «?fhir_vs=refset/<refsetId>», con el signo "
        f"igual: {mal_escritas}"
    )


def test_la_uri_de_edicion_no_se_usa_como_system_de_un_coding() -> None:
    """El `system` de un `Coding` de SNOMED es siempre `http://snomed.info/sct`, sin edición.

    La edición va en `version`, y es exactamente lo que publica el `CodeSystem` que sube el
    cargador. Un `Coding` con la edición metida en el `system` apunta a un sistema de códigos que no
    existe en el servidor: `$lookup` y `$validate-code` contestan que no conocen ese código, y lo
    hacen con la misma cara con la que contestarían a un código inventado.

    La tentación tiene nombre y está declarada: `$SCT_ES`, que es correcta como base de un
    `ValueSet` implícito y veneno delante de una almohadilla.
    """
    alias_de_edicion = [
        f"${nombre}"
        for nombre, uri in DECLARACION.findall(ALIASES.read_text(encoding="utf-8"))
        if EDICION.match(uri)
    ]
    literal = re.compile(r"http://snomed\.info/sct/\d+(/version/\d{8})?#")

    usos = [
        f"{fichero.relative_to(FSH)}: {linea.strip()}"
        for fichero in _ficheros()
        for linea in fichero.read_text(encoding="utf-8").splitlines()
        if not linea.lstrip().startswith("//")
        and (literal.search(linea) or any(f"{alias}#" in linea for alias in alias_de_edicion))
    ]

    assert not usos, (
        "El `system` de un `Coding` de SNOMED es `http://snomed.info/sct` y la edición va en "
        f"`version`: {usos}"
    )
