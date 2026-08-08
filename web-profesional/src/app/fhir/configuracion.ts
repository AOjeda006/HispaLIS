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
 * Dónde está el **servidor de terminología**, que es quien contesta qué pruebas oferta el catálogo.
 *
 * Relativa por lo mismo que {@link BASE_FHIR}, y detrás del mismo proxy. Lo que hay al otro lado es
 * un servidor de terminología FHIR cualquiera: la web le habla con `$expand` de la API estándar, así
 * que cambiarlo por Snowstorm o por Ontoserver no toca ni una línea de este cliente (D14).
 */
export const BASE_TERMINOLOGIA = new InjectionToken<string>('servidor de terminología', {
  providedIn: 'root',
  factory: () => '/terminologia',
});

/**
 * El `ValueSet` que enumera las pruebas que se pueden pedir.
 *
 * Es una URL **canónica**, no una ruta: identifica el conjunto, no dónde está guardado. Quien lo
 * resuelve es el servidor de terminología, y por eso el mismo literal vale contra cualquiera.
 */
export const VS_PRUEBAS_DEL_CATALOGO = new InjectionToken<string>('ValueSet de pruebas', {
  providedIn: 'root',
  factory: () => 'https://aojeda006.github.io/HispaLIS/fhir/ValueSet/pruebas-del-catalogo',
});
