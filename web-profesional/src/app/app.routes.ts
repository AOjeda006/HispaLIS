import { Routes } from '@angular/router';

import { guardaDeSesion } from './seguridad/guarda-de-sesion';

/**
 * Las rutas de la web: dos pantallas de trabajo y las dos del lanzamiento SMART.
 *
 * `loadComponent` no es ceremonia: cada pantalla arrastra su ViewModel y sus formularios, y quien
 * solo consulta informes no tiene por qué descargarse el alta de petición.
 *
 * `/launch` y `/callback` **no llevan guarda**, y es lo lógico: son justamente por donde se entra sin
 * sesión. La guarda va en las pantallas clínicas, y lo que hace es mandar a `/launch` a quien llegue
 * sin sesión — no es control de acceso, que se aplica en el laboratorio (ver `guarda-de-sesion.ts`).
 */
export const routes: Routes = [
  {
    path: 'launch',
    title: 'Entrando · HispaLIS',
    loadComponent: () => import('./seguridad/lanzamiento').then((m) => m.Lanzamiento),
  },
  {
    path: 'callback',
    title: 'Entrando · HispaLIS',
    loadComponent: () => import('./seguridad/callback').then((m) => m.Callback),
  },
  {
    path: 'peticiones/nueva',
    title: 'Alta de petición · HispaLIS',
    canActivate: [guardaDeSesion],
    loadComponent: () => import('./peticiones/alta-peticion').then((m) => m.AltaPeticion),
  },
  {
    path: 'informes',
    title: 'Consulta de informe · HispaLIS',
    canActivate: [guardaDeSesion],
    loadComponent: () => import('./informes/consulta-informe').then((m) => m.ConsultaInforme),
  },
  { path: '', pathMatch: 'full', redirectTo: 'peticiones/nueva' },
  { path: '**', redirectTo: 'peticiones/nueva' },
];
