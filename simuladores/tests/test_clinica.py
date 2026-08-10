"""Pruebas de la parte clínica: que los paneles usen el catálogo y que los valores cuadren.

Los dos primeros tests son la salvaguarda de D15 en su punto más frágil. Los paneles y los rangos
**nombran** códigos del catálogo, y nombrar es la puerta por la que se cuela una lista paralela:
basta con que alguien añada aquí un `"TRIG"` que no existe en la guía para que el generador produzca
resultados de una prueba que el sistema no conoce, sin un solo error.
"""

from __future__ import annotations

import random

import pytest

from generador.clinica import (
    NEGATIVO,
    PANELES,
    POSITIVO,
    RANGOS,
    cuadrar_hemograma,
    dispara_refleja,
    elegir_panel,
    interpretacion_de,
    rango_aplicable,
    resultado_cualitativo,
    valor_cuantitativo,
)
from generador.terminologia import cargar_catalogo, cargar_tipos_de_muestra


def test_todas_las_pruebas_de_los_paneles_existen_en_el_catalogo_de_la_guia() -> None:
    catalogo = cargar_catalogo()

    nombradas = {codigo for panel in PANELES for codigo in panel.codigos}
    inventadas = nombradas - set(catalogo.codigos)

    assert not inventadas, f"estos códigos no están en el catálogo de la guía: {sorted(inventadas)}"


def test_todos_los_tipos_de_muestra_de_los_paneles_estan_en_el_valueset() -> None:
    admitidos = cargar_tipos_de_muestra()

    usados = {panel.tipo_de_muestra for panel in PANELES}

    assert usados <= admitidos, f"tipos fuera del ValueSet: {sorted(usados - admitidos)}"


def test_todas_las_pruebas_con_rango_existen_en_el_catalogo() -> None:
    catalogo = cargar_catalogo()

    inventadas = set(RANGOS) - set(catalogo.codigos)

    assert not inventadas, f"rangos de pruebas que no existen: {sorted(inventadas)}"


def test_toda_prueba_cuantitativa_del_catalogo_tiene_rango() -> None:
    # Sin rango, un resultado se publica como una cifra suelta: «4,2» no significa nada, y el
    # criterio de aceptación 10 exige que el valor se presente siempre con unidad y rango.
    catalogo = cargar_catalogo()

    sin_rango = {prueba.codigo for prueba in catalogo.cuantitativas} - set(RANGOS)

    assert not sin_rango, f"pruebas cuantitativas sin rango de referencia: {sorted(sin_rango)}"


def test_el_hematocrito_cuadra_con_la_hemoglobina() -> None:
    azar = random.Random(42)

    for _ in range(200):
        hemograma = cuadrar_hemograma({"HB": 14.0, "HTO": 30.0, "LEU": 7.0}, azar)
        # La regla de los tres: sin este ajuste salen hemoglobinas de 16 con hematocritos de 37,
        # que es una combinación que no existe en ningún paciente.
        assert 2.8 <= hemograma["HTO"] / hemograma["HB"] <= 3.2
        assert hemograma["LEU"] == 7.0, "cuadrar el hemograma no debe tocar lo demás"


def test_cuadrar_un_panel_que_no_es_hemograma_no_cambia_nada() -> None:
    bioquimica = {"GLU": 92.0, "CREA": 0.9}

    assert cuadrar_hemograma(bioquimica, random.Random(42)) == bioquimica


@pytest.mark.parametrize(
    ("valor", "esperado"),
    [(60, "L"), (92, "N"), (140, "H")],
)
def test_la_interpretacion_sigue_al_rango(valor: float, esperado: str) -> None:
    assert interpretacion_de("GLU", valor, "male") == esperado


def test_el_rango_de_la_serie_roja_depende_del_sexo() -> None:
    # Una hemoglobina de 13 g/dL es normal en una mujer y baja en un hombre. Sin rango por sexo, la
    # mitad de las interpretaciones de la serie roja están mal.
    assert interpretacion_de("HB", 13.0, "female") == "N"
    assert interpretacion_de("HB", 13.0, "male") == "L"


def test_una_prueba_sin_rango_no_tiene_interpretacion() -> None:
    assert rango_aplicable("LEGIOAG", "male") is None
    assert interpretacion_de("LEGIOAG", 1.0, "male") is None


def test_los_valores_generados_caen_casi_siempre_dentro_del_rango() -> None:
    azar = random.Random(42)

    valores = [valor_cuantitativo("GLU", "male", azar) for _ in range(1000)]
    normales = sum(1 for valor in valores if interpretacion_de("GLU", valor, "male") == "N")

    # Ni todos normales —no se probaría la interpretación— ni la mitad alterados, que no se parece
    # a ningún laboratorio.
    assert 0.65 < normales / len(valores) < 0.90
    assert all(valor >= 0 for valor in valores)


def test_una_tsh_alta_dispara_la_refleja_y_una_normal_no() -> None:
    assert dispara_refleja("TSH", 9.0, "female")
    assert not dispara_refleja("TSH", 2.0, "female")
    # Una TSH baja tampoco: el protocolo añade T4 libre cuando la TSH está por encima.
    assert not dispara_refleja("TSH", 0.1, "female")
    assert not dispara_refleja("GLU", 300.0, "female")


def test_los_cualitativos_salen_casi_todos_negativos() -> None:
    azar = random.Random(42)

    resultados = [resultado_cualitativo(azar) for _ in range(1000)]
    positivos = sum(1 for *_, interpretacion in resultados if interpretacion == "POS")

    assert 0 < positivos < len(resultados) * 0.2, "en microbiología casi todo sale negativo"


def test_el_cualitativo_trae_el_codigo_local_y_el_de_snomed() -> None:
    """Los dos, y el local primero.

    El laboratorio compara contra SU catálogo para decidir si hay que declarar a Salud Pública, así
    que un resultado que solo trajera SNOMED no dispararía la regla. Y uno que solo trajera el
    código local no lo entendería quien lo recibe sin conocer este dialecto.
    """
    azar = random.Random(1)

    locales = {resultado_cualitativo(azar)[0] for _ in range(200)}
    snomeds = {resultado_cualitativo(azar)[2] for _ in range(200)}

    assert locales <= {"POS", "NEG"}
    assert snomeds <= {POSITIVO, NEGATIVO}


def test_elegir_panel_respeta_los_pesos() -> None:
    azar = random.Random(42)

    elegidos = [elegir_panel(azar).nombre for _ in range(2000)]

    assert elegidos.count("Bioquímica básica") > elegidos.count("Coprocultivo")
    assert set(elegidos) == {panel.nombre for panel in PANELES}, "algún panel no sale nunca"
