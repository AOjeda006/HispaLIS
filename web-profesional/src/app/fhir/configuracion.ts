import { InjectionToken } from '@angular/core';

/**
 * Dónde está la API FHIR del laboratorio.
 *
 * Es **relativa a propósito**. Poner aquí un `http://…` metería el nombre de la máquina en el
 * paquete que se descarga el navegador —habría que reconstruir la web para cambiar de entorno— y
 * convertiría cada llamada en una petición de origen cruzado, con su CORS y su *preflight*. La web
 * se sirve detrás del mismo proxy que la API (el servidor de desarrollo de Angular en local, el
 * `compose` del ítem 15 después), así que para el navegador todo es el mismo origen.
 *
 * Es un `InjectionToken` y no una constante para que un test pueda sustituirlo sin tocar el código
 * que lo usa.
 */
export const BASE_FHIR = new InjectionToken<string>('base de la API FHIR', {
  providedIn: 'root',
  factory: () => '/fhir',
});

/**
 * Dónde está el catálogo de pruebas que publica la guía.
 *
 * Lo trae `scripts/traer-terminologia.mjs` de `ig/fsh-generated/`: es el **mismo** `CodeSystem` que
 * usan el backend y el generador, no una copia editada a mano (D15).
 */
export const URL_CATALOGO = new InjectionToken<string>('catálogo de pruebas', {
  providedIn: 'root',
  factory: () => 'terminologia/CodeSystem-catalogo-pruebas.json',
});
