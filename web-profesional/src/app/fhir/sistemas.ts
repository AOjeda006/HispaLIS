/**
 * Los `Identifier.system` que esta web necesita nombrar.
 *
 * Un identificador sin `system` no identifica nada: «12345678» puede ser un número de historia, un
 * DNI sin letra o un número de afiliación.
 *
 * La **fuente de verdad** es `ig/input/fsh/aliases.fsh`, y esto la repite porque un navegador no
 * puede leer el FSH. Repetir es aceptable; **divergir en silencio, no**: hay un test que cruza estas
 * dos constantes contra ese fichero y falla si dejan de coincidir. Es el mismo trato que se le da a
 * la copia del backend.
 */

/** Número de historia clínica del laboratorio. Propio: lo emitimos nosotros. */
export const SID_NHC = 'https://aojeda006.github.io/HispaLIS/sid/nhc';

/** DNI o NIE. OID del registro español, adoptado del Ministerio (D21). */
export const SID_DNI_NIE = 'urn:oid:1.3.6.1.4.1.19126.3';

/**
 * Número de colegiado del facultativo peticionario.
 *
 * La guía declara además un `system` por colegio emisor (`…/sid/colegiado/com-sevilla`), porque el
 * número solo es único dentro de su colegio. La web usa el genérico: identificar el colegio exige
 * preguntarlo, y en el volante de un laboratorio privado casi nunca viene.
 */
export const SID_COLEGIADO = 'https://aojeda006.github.io/HispaLIS/sid/colegiado';

/**
 * Escribe un criterio de búsqueda por identificador, con su `system`.
 *
 * Buscar solo por el valor encontraría a cualquiera cuyo DNI coincida con un número de historia.
 */
export function porIdentificador(system: string, valor: string): string {
  return `${system}|${valor}`;
}
