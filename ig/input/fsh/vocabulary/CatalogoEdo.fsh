ValueSet: CatalogoEdo
Id: catalogo-edo
Title: "Catálogo EDO — pruebas que obligan a declarar"
Description: """
Pruebas del catálogo cuyo resultado positivo obliga a **declarar a Salud Pública**.

Es la regla de negocio con motivo legal del proyecto: todos los centros sanitarios de Andalucía,
públicos *y privados*, forman parte del Sistema de Vigilancia Epidemiológica de Andalucía (Decreto
66/1996), y la relación de enfermedades de declaración obligatoria la fija la Orden de 19 de
diciembre de 1996, actualizada por la de 12 de noviembre de 2015. Cuando se valida un resultado
positivo de una de estas pruebas, se genera una `NotificacionEDO`.

**Este conjunto es verosímil, no fiel.** La relación real de EDO es mucho más amplia y el contrato de
Redalerta no es público: aquí hay una muestra suficiente para que la regla sea demostrable.

No es el `binding` de ningún elemento y **no es la regla**: es la lista, publicada para poder leerla
de un vistazo. La regla que ejecuta el laboratorio son las propiedades `enfermedad-edo` y
`resultado-que-declara` del concepto en `CodeSystem/catalogo-pruebas`, que dicen además *qué*
enfermedad se declara y *con qué* resultado — dos cosas que una lista de códigos no puede decir.
Estar aquí y tener esas propiedades es lo mismo, y en el laboratorio se comprueba que no se
desincronizan.

Que exista de todas formas no es redundancia: un clínico que quiera saber qué pruebas de este
catálogo acaban en una declaración a Salud Pública lo lee aquí, sin tener que recorrer concepto a
concepto.
"""

* CatalogoPruebas#LEGIOAG
* CatalogoPruebas#COPROSALM
* CatalogoPruebas#MTBPCR
* CatalogoPruebas#VHAIGM
* CatalogoPruebas#SARIGM
