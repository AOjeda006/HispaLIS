import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Sesion } from './sesion';

/**
 * Sin sesión no se entra en una pantalla clínica: se lanza el flujo autónomo.
 *
 * **Esto no es control de acceso y no hay que confundirlo con uno.** Quien decide qué datos salen es
 * el laboratorio, que comprueba el testigo en cada petición; esta guarda solo evita que el
 * profesional se encuentre una pantalla que va a contestar `401` en cuanto pida algo. Quitarla no
 * abriría ningún dato — dejaría una pantalla rota, que es peor experiencia y ninguna vulnerabilidad.
 *
 * El destino se lleva en la propia URL de lanzamiento para volver donde se quería ir. No lleva PHI:
 * son rutas de la aplicación, no búsquedas.
 */
export const guardaDeSesion: CanActivateFn = (ruta, estado) => {
  const sesion = inject(Sesion);
  const enrutador = inject(Router);

  if (sesion.activa()) {
    return true;
  }
  return enrutador.createUrlTree(['/launch'], { queryParams: { destino: estado.url } });
};
