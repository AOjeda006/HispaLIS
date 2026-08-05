import { Routes } from '@angular/router';

/**
 * Las dos pantallas del hito 1, cargadas cuando se entra en ellas.
 *
 * `loadComponent` no es ceremonia: cada pantalla arrastra su ViewModel y sus formularios, y quien
 * solo consulta informes no tiene por qué descargarse el alta de petición.
 */
export const routes: Routes = [
  {
    path: 'peticiones/nueva',
    title: 'Alta de petición · HispaLIS',
    loadComponent: () => import('./peticiones/alta-peticion').then((m) => m.AltaPeticion),
  },
  {
    path: 'informes',
    title: 'Consulta de informe · HispaLIS',
    loadComponent: () => import('./informes/consulta-informe').then((m) => m.ConsultaInforme),
  },
  { path: '', pathMatch: 'full', redirectTo: 'peticiones/nueva' },
  { path: '**', redirectTo: 'peticiones/nueva' },
];
