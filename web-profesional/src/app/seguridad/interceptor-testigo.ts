import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { BASE_FHIR } from '../fhir/configuracion';
import { Sesion } from './sesion';

/**
 * Pone el testigo en las llamadas a la API del laboratorio, y **solo** en esas.
 *
 * Que sea «solo en esas» es la parte importante. Un interceptor que añadiera la cabecera a todo
 * mandaría el testigo del laboratorio al servidor de autorización cuando se canjea el código, al
 * fichero del catálogo, y a cualquier URL que alguien añada mañana. Un testigo enviado a quien no le
 * corresponde es un testigo entregado.
 *
 * Va como interceptor y no dentro de `ClienteFhir` porque el cliente FHIR va de FHIR: quién firma la
 * petición es una preocupación distinta, y separarlas es lo que permite que la web funcione igual con
 * la seguridad apagada.
 */
export const interceptorDeTestigo: HttpInterceptorFn = (peticion, siguiente) => {
  const sesion = inject(Sesion);
  const base = inject(BASE_FHIR);
  const testigo = sesion.testigo();

  if (!testigo || !vaAlLaboratorio(peticion.url, base)) {
    return siguiente(peticion);
  }
  return siguiente(peticion.clone({ setHeaders: { Authorization: `Bearer ${testigo}` } }));
};

/**
 * ¿Esta URL es del laboratorio?
 *
 * Se comprueban las dos formas en las que aparece: la relativa que construye {@link BASE_FHIR}, y la
 * absoluta del **mismo origen** que devuelve el servidor en `Bundle.link[relation=next]`. La segunda
 * es fácil de olvidar y el fallo es exasperante: la primera página de una búsqueda funciona y la
 * segunda contesta `401`.
 */
function vaAlLaboratorio(url: string, base: string): boolean {
  if (url.startsWith(base)) {
    return true;
  }
  try {
    const destino = new URL(url, globalThis.location.origin);
    return destino.origin === globalThis.location.origin && destino.pathname.startsWith(base);
  } catch {
    return false;
  }
}
