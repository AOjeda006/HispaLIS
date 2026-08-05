import { Observation, Patient, RangoDeReferencia, SEXO_EN_SNOMED } from './tipos';

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
 * Los apellidos del paciente, **completos y sin partir**.
 *
 * `HumanName.family` ya trae el nombre familiar entero, así que esto solo lo devuelve. Parece que no
 * hace nada y esa es la gracia: el error habitual es cortar por el primer espacio para «separar los
 * dos apellidos», y con «de la Torre Gómez» eso produce «de» y «la Torre Gómez». En un laboratorio,
 * confundir apellidos es confundir pacientes.
 */
export function apellidos(paciente: Patient): string {
  return paciente.name?.[0]?.family ?? '';
}

/** El nombre de pila y los apellidos, como se escribe en España. */
export function nombreCompleto(paciente: Patient): string {
  const nombre = paciente.name?.[0];
  const pila = nombre?.given?.join(' ') ?? '';
  return [pila, nombre?.family ?? ''].filter((parte) => parte.length > 0).join(' ');
}

/**
 * El primer y el segundo apellido por separado, cuando de verdad hacen falta.
 *
 * Salen de las extensiones y **nunca** de partir `family`. Si el recurso no las trae, se devuelve
 * vacío: es información que no consta, y eso es distinto de adivinarla.
 */
export function apellidosPorSeparado(paciente: Patient): { padre?: string; madre?: string } {
  const extensiones = paciente.name?.[0]?._family?.extension ?? [];
  return {
    padre: extensiones.find((extension) => extension.url === APELLIDO_PADRE)?.valueString,
    madre: extensiones.find((extension) => extension.url === APELLIDO_MADRE)?.valueString,
  };
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

/** Números a la española: coma decimal y sin ceros de relleno. */
function formatear(valor: number): string {
  return new Intl.NumberFormat('es-ES', { maximumFractionDigits: 4 }).format(valor);
}
