// =============================================================================
// RuleSets compartidos.
//
// Tres patrones se repiten entre perfiles y copiarlos sería deuda garantizada: el juego de
// extensiones de apellidos españoles, el código INE sobre una dirección y la cabecera de `slicing`
// de los identificadores. Viven aquí para que corregirlos sea corregir un sitio.
// =============================================================================


// Apellidos españoles sobre `HumanName` (§4.2, §6.2 del diseño).
//
// ⚠️ Las dos extensiones se declaran sobre el elemento `family`, NO sobre `HumanName`: ese es su
// contexto oficial y declararlas en el nombre completo hace que la IG no compile.
//
// `family` lleva SIEMPRE el nombre familiar completo («de la Torre Muñoz»); las extensiones lo
// descomponen para quien necesite las partes. Nunca se parte por el espacio: «de la Torre Gómez» y
// «Fernández de Córdoba Ruiz» rompen ese heurístico, y en un laboratorio confundir apellidos es
// confundir pacientes.
RuleSet: ApellidosEspanoles
* name.family MS
* name.family ^short = "Nombre familiar COMPLETO, sin partir (p. ej. «de la Torre Muñoz»)"
* name.family.extension contains
    $EXT_APELLIDO_PADRE named apellidoPadre 0..1 MS and
    $EXT_APELLIDO_MADRE named apellidoMadre 0..1 MS
* name.family.extension[apellidoPadre] ^short = "Primer apellido, ya contenido en `family`"
* name.family.extension[apellidoMadre] ^short = "Segundo apellido, ya contenido en `family`"


// Código INE de provincia y municipio sobre una dirección (§4.3, D9). Se parametriza la ruta porque
// en R5 la dirección de una `Organization` cuelga de `contact`, no del recurso.
RuleSet: CodigoIneEnDireccion(ruta)
* {ruta}.extension contains $EXT_CODIGO_INE named codigoIne 0..1 MS
* {ruta}.extension[codigoIne] ^short = "Códigos INE de provincia y municipio"


// Cabecera de `slicing` de `identifier`, discriminando por `system`.
//
// El discriminador OBLIGA a fijar `system` en cada slice: eso no contradice a D16, que prohíbe
// `pattern` y regex sobre el `value` de los identificadores que el laboratorio no emite. El `system`
// es justamente lo que dice de qué registro procede el código; el `value` queda opaco.
//
// El slicing se deja ABIERTO: un paciente puede traer identificadores que esta guía no enumera.
RuleSet: SlicingIdentificadorPorSystem(descripcion)
* identifier ^slicing.discriminator[0].type = #value
* identifier ^slicing.discriminator[0].path = "system"
* identifier ^slicing.rules = #open
* identifier ^slicing.description = "{descripcion}"
