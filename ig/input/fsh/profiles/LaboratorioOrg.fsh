Profile: LaboratorioOrg
Parent: Organization
Id: laboratorio-org
Title: "Laboratorio o centro sanitario"
Description: """
Centro sanitario español: el propio laboratorio o la clínica que le remite peticiones.

El **NICA** es obligatorio. Es el código del Registro Andaluz de Centros, Servicios y
Establecimientos Sanitarios (Decreto 69/2008), y sin autorización sanitaria —de la que el NICA es la
constancia— no hay laboratorio que valga. Es además público y consultable, así que un NICA
inventado se detecta.

Este perfil **no** se aplica a las aseguradoras: una mutua no es un centro sanitario y no tiene
NICA. En `CoberturaLab` la aseguradora se referencia como `Organization` sin perfilar.

⚠️ **R5:** `Organization` **ya no tiene `address` ni `telecom`**. Ambos se sustituyeron por
`contact`, de tipo `ExtendedContactDetail`. Un `Organization` de R4 no valida en R5 por este motivo.
"""

* insert SlicingIdentificadorPorSystem(Códigos de registro sanitario y fiscal del centro.)

* identifier contains
    nica 1..1 MS and
    nif 0..1 MS

* identifier[nica].system = $SID_NICA
* identifier[nica].value 1..1
* identifier[nica] ^short = "NICA del Registro Andaluz de Centros, Servicios y Establecimientos Sanitarios"

* identifier[nif].system = $SID_NIF
* identifier[nif] ^short = "NIF de la entidad titular"

* active MS
* name 1..1 MS
* type MS

* contact MS
* contact.address MS
* insert CodigoIneEnDireccion(contact.address)
* contact.telecom MS
