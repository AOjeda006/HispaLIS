import {
  DisparadaPor,
  HumanName,
  Identificador,
  Observation,
  RangoDeReferencia,
  SEXO_EN_SNOMED,
} from './tipos';

/**
 * Cómo se enseñan en pantalla un nombre y un resultado.
 *
 * Las dos funciones de aquí existen por la misma razón: en una herramienta clínica, presentar mal un
 * dato **es** un error, no un detalle estético. Un apellido partido convierte a un paciente en otro,
 * y una cifra sin unidad ni rango no se puede interpretar.
 */

/** Extensiones estándar que descomponen el nombre familiar. */
const APELLIDO_PADRE = 'http://hl7.org/fhir/StructureDefinition/humanname-fathers-family';
const APELLIDO_MADRE = 'http://hl7.org/fhir/StructureDefinition/humanname-mothers-family';

/**
 * Cualquier recurso con nombre de persona: un paciente, un facultativo.
 *
 * Se escribe por su forma y no por el tipo de recurso porque las reglas del nombre español —el
 * apellido entero, sin partir— valen igual para los dos, y no hay ninguna razón para tener dos
 * funciones idénticas que se diferencien solo en su declaración.
 */
interface ConNombre {
  readonly name?: readonly HumanName[];
}

/**
 * Los apellidos del paciente, **completos y sin partir**.
 *
 * `HumanName.family` ya trae el nombre familiar entero, así que esto solo lo devuelve. Parece que no
 * hace nada y esa es la gracia: el error habitual es cortar por el primer espacio para «separar los
 * dos apellidos», y con «de la Torre Gómez» eso produce «de» y «la Torre Gómez». En un laboratorio,
 * confundir apellidos es confundir pacientes.
 */
export function apellidos(persona: ConNombre): string {
  return persona.name?.[0]?.family ?? '';
}

/** El nombre de pila y los apellidos, como se escribe en España. */
export function nombreCompleto(persona: ConNombre): string {
  const nombre = persona.name?.[0];
  const pila = nombre?.given?.join(' ') ?? '';
  return [pila, nombre?.family ?? ''].filter((parte) => parte.length > 0).join(' ');
}

/**
 * El primer y el segundo apellido por separado, cuando de verdad hacen falta.
 *
 * Salen de las extensiones y **nunca** de partir `family`. Si el recurso no las trae, se devuelve
 * vacío: es información que no consta, y eso es distinto de adivinarla.
 */
export function apellidosPorSeparado(persona: ConNombre): { padre?: string; madre?: string } {
  const extensiones = persona.name?.[0]?._family?.extension ?? [];
  return {
    padre: extensiones.find((extension) => extension.url === APELLIDO_PADRE)?.valueString,
    madre: extensiones.find((extension) => extension.url === APELLIDO_MADRE)?.valueString,
  };
}

/**
 * El valor de un identificador concreto, elegido por su `system`.
 *
 * Coger «el primero» encontraría el DNI donde se esperaba el número de historia, y los dos son
 * cadenas de dígitos indistinguibles a ojo.
 */
export function identificador(
  recurso: { readonly identifier?: readonly Identificador[] },
  system: string,
): string {
  return (recurso.identifier ?? []).find((suyo) => suyo.system === system)?.value ?? '';
}

/**
 * Una marca de tiempo de FHIR, escrita como se lee en España.
 *
 * Se muestra con la hora y no solo con el día: en un laboratorio, dos informes del mismo paciente
 * el mismo día son lo normal, y sin la hora no se distinguen.
 */
export function fechaLegible(marca?: string): string {
  if (!marca) {
    return '';
  }
  const momento = new Date(marca);
  if (Number.isNaN(momento.getTime())) {
    return marca;
  }
  return new Intl.DateTimeFormat('es-ES', { dateStyle: 'short', timeStyle: 'short' }).format(
    momento,
  );
}

/** Lo que se muestra cuando el resultado no trae valor. Nunca se deja el hueco en blanco. */
export const SIN_VALOR = 'Sin resultado';

/**
 * El valor del resultado con su unidad pegada.
 *
 * La unidad sale de `valueQuantity.unit` —la que el laboratorio imprime— y no de `code`, que es el
 * código UCUM y está para convertir, no para leer. Nunca se devuelve la cifra sola: «4,2» es normal
 * para un potasio y alto para una creatinina.
 */
export function valorConUnidad(resultado: Observation): string {
  const cantidad = resultado.valueQuantity;
  if (cantidad?.value !== undefined) {
    const unidad = cantidad.unit ?? cantidad.code;
    return unidad ? `${formatear(cantidad.value)} ${unidad}` : formatear(cantidad.value);
  }
  if (resultado.valueString) {
    return resultado.valueString;
  }

  const codificado = resultado.valueCodeableConcept;
  return codificado?.text ?? codificado?.coding?.[0]?.display ?? SIN_VALOR;
}

/**
 * El rango de referencia que le corresponde a este paciente, escrito para leerlo.
 *
 * El laboratorio publica **todos** los rangos de la prueba, cada uno diciendo con `appliesTo` a
 * quién aplica: la proyección no conoce al paciente y no puede elegir por él. Elegir es cosa de
 * quien sí lo conoce, que es esta pantalla. Sin esa elección se mostraría el rango de un hombre a
 * una mujer, que en la serie roja cambia la lectura del resultado.
 *
 * @param sexo el sexo del paciente, o indefinido si no consta
 * @returns el rango con su unidad, o cadena vacía si la prueba no tiene rango (las cualitativas)
 */
export function rangoDeReferencia(resultado: Observation, sexo?: string): string {
  const rango = elegirRango(resultado.referenceRange ?? [], sexo);
  if (!rango) {
    return '';
  }

  const unidad = rango.low?.unit ?? rango.high?.unit ?? '';
  const bajo = rango.low?.value;
  const alto = rango.high?.value;

  if (bajo !== undefined && alto !== undefined) {
    return `${formatear(bajo)} – ${formatear(alto)} ${unidad}`.trim();
  }
  if (alto !== undefined) {
    return `hasta ${formatear(alto)} ${unidad}`.trim();
  }
  if (bajo !== undefined) {
    return `desde ${formatear(bajo)} ${unidad}`.trim();
  }
  return '';
}

/**
 * Elige el rango aplicable: el del sexo del paciente si lo hay, y si no el común.
 *
 * Cuando el sexo no consta se devuelve **solo** el rango común, nunca uno de los dos específicos:
 * enseñar el de hombre a un paciente sin sexo registrado es inventarse un dato clínico.
 */
function elegirRango(
  rangos: readonly RangoDeReferencia[],
  sexo?: string,
): RangoDeReferencia | undefined {
  const codigoDelSexo = sexo ? SEXO_EN_SNOMED[sexo] : undefined;
  const especifico = codigoDelSexo
    ? rangos.find((rango) => aplicaA(rango, codigoDelSexo))
    : undefined;

  return especifico ?? rangos.find((rango) => (rango.appliesTo ?? []).length === 0);
}

function aplicaA(rango: RangoDeReferencia, codigoDelSexo: string): boolean {
  return (rango.appliesTo ?? []).some((poblacion) =>
    (poblacion.coding ?? []).some((codificacion) => codificacion.code === codigoDelSexo),
  );
}

/**
 * Por qué existe esta determinación, **en palabras**.
 *
 * ⚠️ `triggeredBy` es nuevo en R5: antes no había dónde decir esto, y el informe enseñaba dos
 * potasios del mismo día sin nada que aclarase cuál vale.
 *
 * Se enseña con una frase y no con un icono, y no es una preferencia estética. Un icono hay que
 * aprendérselo, no lo lee un lector de pantalla y no distingue una repetición por muestra
 * hemolizada de una re-ejecución por control de calidad fuera — que es justo lo que el que mira la
 * historia necesita saber.
 *
 * La frase la trae el propio recurso en `reason`, redactada por quien redactó la regla. Solo cuando
 * no viene —porque la declaró un tercero que se la dejó— se compone una a partir del tipo: es
 * información de menos, nunca inventada.
 */
export function porQueExiste(resultado: Observation): string {
  const disparo = (resultado.triggeredBy ?? [])[0];
  if (!disparo) {
    return '';
  }
  return disparo.reason?.trim() || porDefecto(disparo);
}

function porDefecto(disparo: DisparadaPor): string {
  switch (disparo.type) {
    case 'reflex':
      return 'Añadida por el laboratorio a partir de otra determinación alterada.';
    case 'repeat':
      return 'Repetición de una determinación anterior, con el mismo método.';
    case 're-run':
      return 'Re-ejecución de una determinación anterior, con otro ajuste del analizador.';
    default:
      // Un código que esta versión no conoce. Decir «derivada de otra» es cierto y no supone nada;
      // callarse dejaría dos cifras sin explicar, que es lo que esto viene a arreglar.
      return 'Derivada de otra determinación.';
  }
}

/** Números a la española: coma decimal y sin ceros de relleno. */
function formatear(valor: number): string {
  return new Intl.NumberFormat('es-ES', { maximumFractionDigits: 4 }).format(valor);
}
