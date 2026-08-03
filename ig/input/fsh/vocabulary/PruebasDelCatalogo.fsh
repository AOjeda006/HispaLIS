ValueSet: PruebasDelCatalogo
Id: pruebas-del-catalogo
Title: "Pruebas del catálogo del laboratorio"
Description: """
Todas las pruebas que el laboratorio oferta. Es el conjunto al que se atan `PeticionLab.code` y
`ResultadoLab.code`.

El *binding* es **extensible**, no `required`: el catálogo crece cuando el laboratorio incorpora una
técnica, y un conjunto que en la práctica no está cerrado no se declara cerrado.
"""

* include codes from system CatalogoPruebas
