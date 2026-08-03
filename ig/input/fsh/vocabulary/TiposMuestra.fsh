ValueSet: TiposMuestra
Id: tipos-muestra
Title: "Tipos de muestra"
Description: """
Tipos de espécimen que el laboratorio acepta, en SNOMED CT.

Los conceptos se enumeran **sin `display`** a propósito: el término lo resuelve el servidor de
terminología, que es quien conoce la edición y el idioma en uso. Escribirlo aquí a mano lo congelaría
y lo dejaría desalineado en cuanto cambiase la versión de SNOMED.

Los términos en español llegan al cargar la **Edición Española** del SNS en el servidor de
terminología (D7, D14). Mientras se resuelva contra la edición internacional, la guía publicada los
mostrará en inglés: es una limitación del servidor, no del conjunto.
"""

// Sangre y sus derivados
* $SCT#119297000
* $SCT#122555007
* $SCT#119361006
* $SCT#119364003

// Orina
* $SCT#122575003

// Respiratorio
* $SCT#119334006
* $SCT#258529004

// Digestivo
* $SCT#119339001

// Otros
* $SCT#258450006
* $SCT#119342007
