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

No es el `binding` de ningún elemento: lo consume la **regla de notificación**, que compara el código
del resultado validado contra este conjunto. Publicarlo como `ValueSet` es lo que impide que esa
lista acabe incrustada en el código.
"""

* CatalogoPruebas#LEGIOAG
* CatalogoPruebas#COPROSALM
* CatalogoPruebas#MTBPCR
* CatalogoPruebas#VHAIGM
* CatalogoPruebas#SARIGM
