import { OperationOutcome } from './tipos';

/**
 * Traduce a un mensaje para el usuario lo que el laboratorio devolvió cuando algo falló.
 *
 * El servidor contesta **siempre** con un `OperationOutcome` y el código HTTP que le toca (criterio
 * de aceptación 9), y ese recurso ya trae el motivo escrito en español y en términos del negocio:
 * «La muestra A20000004 fue rechazada (HEM), así que no puede producir resultados». Volver a
 * redactarlo aquí a partir del código HTTP sería tirar la única explicación que sabe qué pasó y
 * sustituirla por una genérica.
 *
 * Lo que sí se hace es **no enseñar lo que no ayuda**: ni el recurso crudo, ni el prefijo interno de
 * HAPI (`HAPI-0550:`), ni una traza. Quien está delante es un administrativo con un paciente en el
 * mostrador, no quien mantiene el servidor.
 */

/** Lo que se muestra cuando ni siquiera hubo respuesta que interpretar. */
export const SIN_RESPUESTA = 'No se ha podido contactar con el laboratorio. Inténtalo de nuevo.';

/** Lo que se muestra cuando hubo respuesta pero no dice nada aprovechable. */
export const SIN_DETALLE = 'El laboratorio ha rechazado la operación y no ha dicho por qué.';

/** Prefijos que HAPI antepone a sus mensajes y que al usuario no le dicen nada. */
const PREFIJO_INTERNO = /^(HAPI-\d+:\s*)+/;

/**
 * @param cuerpo el cuerpo de la respuesta, si lo hubo
 * @returns el mensaje a mostrar, siempre en español y siempre no vacío
 */
export function mensajeDeError(cuerpo: unknown): string {
  if (!esOperationOutcome(cuerpo)) {
    return SIN_RESPUESTA;
  }

  const motivos = cuerpo.issue
    .filter((incidencia) => incidencia.severity === 'error' || incidencia.severity === 'fatal')
    .map(
      (incidencia) =>
        incidencia.details?.text ??
        incidencia.diagnostics ??
        incidencia.details?.coding?.[0]?.display,
    )
    .filter((motivo): motivo is string => typeof motivo === 'string' && motivo.trim().length > 0)
    .map(limpiar);

  return motivos.length > 0 ? motivos.join(' ') : SIN_DETALLE;
}

/**
 * Indica si lo recibido es un `OperationOutcome` utilizable.
 *
 * Se comprueba la forma y no se confía en el `Content-Type`: un intermediario mal configurado —un
 * portal cautivo, un proxy de empresa— devuelve HTML con la cabecera de la petición original, y
 * enseñarle al usuario un trozo de HTML como si fuese el motivo del rechazo es peor que decirle que
 * no se pudo contactar.
 */
function esOperationOutcome(cuerpo: unknown): cuerpo is OperationOutcome {
  if (typeof cuerpo !== 'object' || cuerpo === null) {
    return false;
  }
  const candidato = cuerpo as Partial<OperationOutcome>;
  return candidato.resourceType === 'OperationOutcome' && Array.isArray(candidato.issue);
}

function limpiar(motivo: string): string {
  const limpio = motivo.replace(PREFIJO_INTERNO, '').trim();
  return /[.!?]$/.test(limpio) ? limpio : `${limpio}.`;
}
