"""Las URI de `ValueSet` implícito de SNOMED que escribe la guía, contra la sintaxis del estándar.

Un `ValueSet` implícito de SNOMED CT no es una URL cualquiera: la especificación define **cinco**
formas y una sola manera de escribirlas, `?fhir_vs`, `?fhir_vs=isa/…`, `?fhir_vs=refset`,
`?fhir_vs=refset/…` y `?fhir_vs=ecl/…`. Escribir el parámetro de otra manera produce una URI que
**parece** correcta, que SUSHI compila sin decir nada y que no resuelve ningún servidor: para él es
un parámetro de consulta con un nombre raro y sin valor.

Este componente es quien carga la terminología, así que es quien tiene que notarlo. Y hay que
comprobarlo desde fuera del FSH porque dentro nada lo comprueba: un alias que todavía no usa ningún
perfil no lo mira ni el validador oficial.
"""

from __future__ import annotations

import re
from pathlib import Path

FSH = Path(__file__).resolve().parents[2] / "ig" / "input" / "fsh"

#: Cualquier URI de SNOMED que pretenda ser un `ValueSet` implícito, esté bien escrita o no.
IMPLICITA = re.compile(r"http://snomed\.info/sct\S*fhir_vs\S*")

#: Las cinco formas del estándar, sobre la URI de SNOMED con edición y versión opcionales.
CORRECTA = re.compile(
    r"^http://snomed\.info/sct(/\d+(/version/\d{8})?)?"
    r"\?fhir_vs(=(isa|refset|ecl)(/\S+)?)?$"
)


def test_las_uris_de_valueset_implicito_son_las_del_estandar() -> None:
    mal_escritas = [
        f"{fichero.relative_to(FSH)}: {uri}"
        for fichero in sorted(FSH.rglob("*.fsh"))
        for uri in IMPLICITA.findall(fichero.read_text(encoding="utf-8"))
        if not CORRECTA.match(uri)
    ]

    assert not mal_escritas, (
        "Un `ValueSet` implícito de SNOMED se escribe «?fhir_vs=refset/<refsetId>», con el signo "
        f"igual: {mal_escritas}"
    )
