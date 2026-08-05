"""Criterio de aceptación 11 (§14 del diseño): el generador de datos sintéticos.

Se prueba sobre el corpus completo y no sobre las piezas: lo que el criterio exige es que
`python -m generador --seed 42` produzca un juego de datos con unas propiedades concretas, y esas
propiedades solo existen cuando todo está montado.

Los apellidos se llevan la mitad de los tests, y con razón. `MUÑOZ`, `ÁLVAREZ` y `PEÑA` prueban que
el charset viaja entero de punta a punta; «de la Torre Gómez» prueba lo contrario de lo que parece:
que **no** se ha partido por el espacio. El segundo es el que de verdad rompe sistemas, porque el
heurístico de cortar por el primer espacio funciona con el 90 % de los pacientes y convierte al otro
10 % en otra persona.
"""

from __future__ import annotations

import json
import re
from datetime import date
from pathlib import Path

import pytest

from generador.__main__ import main
from generador.configuracion import crear_configuracion
from generador.escenario import Corpus, generar
from generador.identificadores import es_documento_valido
from generador.recursos import EXTENSION_APELLIDO_MADRE, EXTENSION_APELLIDO_PADRE
from generador.terminologia import cargar_catalogo

FECHA = date(2026, 8, 5)
PACIENTES = 60


@pytest.fixture(scope="module")
def corpus() -> Corpus:
    """El corpus de una ejecución con la semilla del criterio."""
    configuracion = crear_configuracion(semilla=42, pacientes=PACIENTES, fecha=FECHA)
    return generar(configuracion, cargar_catalogo())


def apellidos(corpus: Corpus) -> list[str]:
    return [paciente["name"][0]["family"] for paciente in corpus.de_tipo("Patient")]


# ─── Los apellidos ───────────────────────────────────────────────────────────


@pytest.mark.parametrize("caso", ["Muñoz", "Álvarez", "Peña"])
def test_los_apellidos_obligatorios_estan_en_el_corpus(corpus: Corpus, caso: str) -> None:
    assert any(caso in familia for familia in apellidos(corpus)), (
        f"«{caso}» no aparece; es obligatorio, y es el que destapa un charset mal declarado"
    )


def test_los_apellidos_compuestos_estan_en_el_corpus(corpus: Corpus) -> None:
    familias = apellidos(corpus)

    assert "de la Torre Gómez" in familias
    assert "Fernández de Córdoba Ruiz" in familias


def test_el_apellido_compuesto_no_se_parte_por_el_espacio(corpus: Corpus) -> None:
    compuesto = next(
        paciente
        for paciente in corpus.de_tipo("Patient")
        if paciente["name"][0]["family"] == "de la Torre Gómez"
    )
    extensiones = {
        extension["url"]: extension["valueString"]
        for extension in compuesto["name"][0]["_family"]["extension"]
    }

    # Partir por el primer espacio daría «de» y «la Torre Gómez». Este es el test que lo impide.
    assert extensiones[EXTENSION_APELLIDO_PADRE] == "de la Torre"
    assert extensiones[EXTENSION_APELLIDO_MADRE] == "Gómez"


def test_family_lleva_siempre_el_nombre_familiar_completo(corpus: Corpus) -> None:
    for paciente in corpus.de_tipo("Patient"):
        nombre = paciente["name"][0]
        extensiones = {
            extension["url"]: extension["valueString"]
            for extension in nombre["_family"]["extension"]
        }
        padre = extensiones[EXTENSION_APELLIDO_PADRE]
        madre = extensiones[EXTENSION_APELLIDO_MADRE]

        assert nombre["family"] == f"{padre} {madre}"


# ─── Los identificadores ─────────────────────────────────────────────────────


def identificador(paciente: dict, terminacion: str) -> str | None:
    for entrada in paciente["identifier"]:
        if entrada["system"].endswith(terminacion):
            return entrada["value"]
    return None


def test_todos_los_pacientes_traen_nhc_de_ocho_digitos(corpus: Corpus) -> None:
    for paciente in corpus.de_tipo("Patient"):
        assert re.fullmatch(r"\d{8}", identificador(paciente, "/nhc") or "")


def test_todos_los_documentos_tienen_digito_de_control_valido(corpus: Corpus) -> None:
    for paciente in corpus.de_tipo("Patient"):
        documento = identificador(paciente, "1.3.6.1.4.1.19126.3")
        assert documento is not None
        assert es_documento_valido(documento), f"documento con letra incorrecta: {documento}"


def test_los_nuhsa_que_hay_tienen_el_formato_correcto(corpus: Corpus) -> None:
    for paciente in corpus.de_tipo("Patient"):
        nuhsa = identificador(paciente, "/nuhsa")
        if nuhsa is not None:
            assert re.fullmatch(r"AN\d{10}", nuhsa)


def test_una_parte_de_los_pacientes_no_trae_nuhsa_ni_cip_sns(corpus: Corpus) -> None:
    pacientes = corpus.de_tipo("Patient")
    sin_nuhsa = [p for p in pacientes if identificador(p, "/nuhsa") is None]
    sin_ninguno = [p for p in sin_nuhsa if identificador(p, "2.16.724.4.40") is None]

    # Es el caso real de un laboratorio privado: mutualistas y privados a menudo ni lo conocen. Un
    # corpus donde todos lo traen deja ese camino sin probar, que es el que más se da en la puerta.
    assert 0 < len(sin_nuhsa) < len(pacientes)
    assert sin_ninguno, "ningún paciente llega sin NUHSA y sin CIP-SNS"


def test_la_direccion_lleva_el_codigo_ine_de_sevilla(corpus: Corpus) -> None:
    for paciente in corpus.de_tipo("Patient"):
        ine = paciente["address"][0]["extension"][0]["extension"]
        partes = {sub["url"]: sub["valueCode"] for sub in ine}

        assert partes["provincia"] == "41"
        assert re.fullmatch(r"\d{3}", partes["municipio"])


# ─── El circuito y la terminología ───────────────────────────────────────────


def test_todo_recurso_declara_el_perfil_al_que_dice_ajustarse(corpus: Corpus) -> None:
    for recurso in corpus.recursos:
        perfiles = recurso["meta"]["profile"]
        assert len(perfiles) == 1
        assert perfiles[0].startswith(
            "https://aojeda006.github.io/HispaLIS/fhir/StructureDefinition/"
        )


def test_los_codigos_de_prueba_salen_del_catalogo_de_la_guia(corpus: Corpus) -> None:
    catalogo = cargar_catalogo()

    for resultado in corpus.de_tipo("Observation"):
        coding = resultado["code"]["coding"][0]
        assert coding["system"] == catalogo.system
        assert coding["code"] in catalogo


def test_una_muestra_rechazada_no_produce_resultados(corpus: Corpus) -> None:
    rechazadas = {
        muestra["id"]
        for muestra in corpus.de_tipo("Specimen")
        if muestra["status"] == "unsatisfactory"
    }
    assert rechazadas, "el corpus no trae ninguna muestra rechazada y no ejercita el invariante C6"

    for muestra in corpus.de_tipo("Specimen"):
        if muestra["status"] == "unsatisfactory":
            assert muestra["condition"], "un rechazo sin motivo incumple `hlis-esp-1`"

    informados = {
        resultado["specimen"]["reference"].removeprefix("Specimen/")
        for resultado in corpus.de_tipo("Observation")
    }
    assert not (rechazadas & informados)


def test_los_resultados_cuantitativos_llevan_siempre_unidad_y_rango(corpus: Corpus) -> None:
    for resultado in corpus.de_tipo("Observation"):
        if "valueQuantity" not in resultado:
            continue
        cantidad = resultado["valueQuantity"]

        assert cantidad["unit"], "una cifra sin unidad no significa nada"
        assert cantidad["system"] == "http://unitsofmeasure.org"
        assert resultado["referenceRange"]


def test_la_prueba_refleja_apunta_a_la_que_la_disparo(corpus: Corpus) -> None:
    resultados = {resultado["id"]: resultado for resultado in corpus.de_tipo("Observation")}
    reflejas = [r for r in resultados.values() if "triggeredBy" in r]

    assert reflejas, "el corpus no dispara ninguna refleja y no ejercita `triggeredBy` de R5"

    for refleja in reflejas:
        disparo = refleja["triggeredBy"][0]
        assert disparo["type"] == "reflex"

        disparador = resultados[disparo["observation"]["reference"].removeprefix("Observation/")]
        assert disparador["code"]["coding"][0]["code"] == "TSH"
        assert disparador["interpretation"][0]["coding"][0]["code"] == "H"
        # La refleja la añade el laboratorio por protocolo, así que no hay volante que la pida.
        assert "basedOn" not in refleja


def test_todo_informe_lleva_al_menos_un_resultado(corpus: Corpus) -> None:
    informes = corpus.de_tipo("DiagnosticReport")

    assert informes
    for informe in informes:
        assert informe["result"], "un informe vacío no es un informe"


def test_las_referencias_apuntan_a_recursos_que_existen(corpus: Corpus) -> None:
    existentes = {f"{r['resourceType']}/{r['id']}" for r in corpus.recursos}
    rotas = [
        referencia
        for recurso in corpus.recursos
        for referencia in _referencias(recurso)
        if referencia not in existentes
    ]

    assert not rotas, f"referencias a recursos que no se generaron: {sorted(set(rotas))[:5]}"


def _referencias(nodo: object) -> list[str]:
    if isinstance(nodo, dict):
        if "reference" in nodo and isinstance(nodo["reference"], str):
            return [nodo["reference"]]
        return [ref for valor in nodo.values() for ref in _referencias(valor)]
    if isinstance(nodo, list):
        return [ref for elemento in nodo for ref in _referencias(elemento)]
    return []


# ─── La reproducibilidad ─────────────────────────────────────────────────────


def test_la_misma_semilla_produce_exactamente_la_misma_salida(tmp_path: Path) -> None:
    primera, segunda = tmp_path / "1", tmp_path / "2"

    for destino in (primera, segunda):
        orden = ["--seed", "42", "--pacientes", "12", "--fecha", "2026-08-05"]
        assert main([*orden, "--salida", str(destino)]) == 0

    assert _volcado(primera) == _volcado(segunda), (
        "sin reproducibilidad el generador no sirve de arnés: no se puede comparar nada"
    )


def test_una_semilla_distinta_produce_una_salida_distinta(tmp_path: Path) -> None:
    # Control negativo. Sin él, un generador que devolviese siempre lo mismo —ignorando la semilla—
    # pasaría el test de arriba con matrícula de honor.
    primera, segunda = tmp_path / "1", tmp_path / "2"

    main(["--seed", "42", "--pacientes", "12", "--fecha", "2026-08-05", "--salida", str(primera)])
    main(["--seed", "77", "--pacientes", "12", "--fecha", "2026-08-05", "--salida", str(segunda)])

    assert _volcado(primera) != _volcado(segunda)


def _volcado(directorio: Path) -> dict[str, str]:
    return {
        ruta.name: ruta.read_text(encoding="utf-8")
        for ruta in sorted((directorio / "recursos").iterdir())
    }


# ─── La orden ────────────────────────────────────────────────────────────────


def test_la_orden_escribe_los_recursos_y_el_manifiesto(tmp_path: Path) -> None:
    assert main(["--seed", "42", "--pacientes", "10", "--salida", str(tmp_path)]) == 0

    manifiesto = json.loads((tmp_path / "manifiesto.json").read_text(encoding="utf-8"))

    assert manifiesto["generadoCon"]["semilla"] == 42
    assert manifiesto["recuento"]["Patient"] == 10
    assert list((tmp_path / "recursos").iterdir())


def test_el_manifiesto_no_se_escribe_entre_los_recursos(tmp_path: Path) -> None:
    # El validador oficial recorre el directorio entero y falla con cualquier JSON que no sea un
    # recurso FHIR. Un manifiesto suelto ahí convierte la comprobación de conformidad en un error
    # de parseo.
    main(["--seed", "42", "--pacientes", "3", "--salida", str(tmp_path)])

    for ruta in (tmp_path / "recursos").iterdir():
        contenido = json.loads(ruta.read_text(encoding="utf-8"))
        assert "resourceType" in contenido


def test_una_fecha_que_no_es_una_fecha_termina_con_codigo_de_error(tmp_path: Path) -> None:
    assert main(["--fecha", "el martes", "--salida", str(tmp_path)]) == 2
