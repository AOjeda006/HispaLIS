// La ÚNICA extensión propia del proyecto (D9, §6.4). Cualquier extensión propia adicional debe
// justificarse por escrito contra la tabla de §6.1 del diseño antes de crearse.

Extension: CodigoIne
Id: codigo-ine
Title: "Código INE de provincia y municipio"
Description: """
Códigos oficiales del Instituto Nacional de Estadística para la provincia y el municipio de una
dirección española. Se usa sobre `Address` cuando hace falta agregar o cruzar por territorio —el
caso concreto de este proyecto es la notificación epidemiológica, que se declara por municipio de
residencia, no por texto libre.

No existe elemento ni extensión estándar para esto: `Address.city` y `Address.state` son cadenas de
texto, y dos formas de escribir el mismo municipio no se agregan.

Los códigos son los del INE: **provincia** de dos dígitos (Sevilla es `41`) y **municipio** de tres
dígitos dentro de la provincia. El código municipal completo del INE, de cinco dígitos, es la
concatenación de ambos.
"""
Context: Address

* extension contains
    provincia 1..1 MS and
    municipio 0..1 MS

* extension[provincia].value[x] only code
* extension[provincia] ^short = "Código INE de provincia, dos dígitos"
* extension[municipio].value[x] only code
* extension[municipio] ^short = "Código INE de municipio dentro de la provincia, tres dígitos"

// Extensión compleja: el valor vive en las sub-extensiones.
* value[x] 0..0
