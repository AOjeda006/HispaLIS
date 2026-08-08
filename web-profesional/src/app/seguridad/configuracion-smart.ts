import { InjectionToken } from '@angular/core';

/**
 * Lo que esta aplicación necesita saber de sí misma para lanzarse con SMART.
 *
 * Son `InjectionToken` y no constantes por lo de siempre: un test los sustituye sin tocar el código
 * que los usa. Y **ninguno es un secreto**: el `client_id` de un cliente público es tan público como
 * su nombre, y la redirección la puede leer cualquiera en la barra del navegador.
 */

/** Con qué identificador está dada de alta esta web en el <em>realm</em>. */
export const CLIENTE_ID = new InjectionToken<string>('client_id de la web', {
  providedIn: 'root',
  factory: () => 'hispalis-web',
});

/**
 * A dónde vuelve el navegador con el código.
 *
 * Se calcula del origen en el que se está sirviendo en vez de escribirse: si estuviera fijada, el
 * mismo paquete no valdría para desarrollo y para el `compose`, y habría que reconstruir la web para
 * cambiar de máquina. El servidor de autorización la comprueba contra su lista, que es donde debe
 * comprobarse.
 */
export const REDIRECCION = new InjectionToken<string>('redirect_uri', {
  providedIn: 'root',
  factory: () => `${globalThis.location.origin}/callback`,
});

/**
 * El emisor por defecto para el lanzamiento autónomo.
 *
 * En **EHR launch** no se usa: el `iss` lo dice el sistema que lanza, y hacerle caso a él —después de
 * comprobar que está en la lista de confianza— es lo que hace que la aplicación sirva para más de una
 * instalación. Esto es solo para cuando alguien entra por la puerta, sin lanzador.
 */
export const ISS_POR_DEFECTO = new InjectionToken<string>('iss del lanzamiento autónomo', {
  providedIn: 'root',
  factory: () => `${globalThis.location.origin}/fhir`,
});

/**
 * Los `iss` que esta aplicación acepta.
 *
 * **No es paranoia: es la vulnerabilidad clásica del EHR launch.** El `iss` llega por la URL y decide
 * a qué servidor de autorización se manda al usuario y a qué servidor se le presenta el testigo. Sin
 * lista, basta un enlace con un `iss` de un servidor del atacante para que la aplicación pida un
 * testigo allí y luego lo mande al laboratorio de verdad — o al revés.
 */
export const ISS_DE_CONFIANZA = new InjectionToken<readonly string[]>('emisores de confianza', {
  providedIn: 'root',
  factory: () => [`${globalThis.location.origin}/fhir`],
});

/**
 * Los <em>scopes</em> que se piden.
 *
 * `user/*.rs` es el permiso de lectura del profesional: todo lo que **él** puede ver, que no es «lo
 * suyo» ni «todo». Los tres `.c` no sobran: **el alta de petición crea recursos**, y con `user/*.rs`
 * a secas la pantalla de alta contestaría `403` en el momento de guardar. Se piden los tres tipos que
 * el alta escribe y ni uno más — pedir `user/*.cruds` daría de paso permiso para borrar informes.
 *
 * `launch` es lo que convierte esto en EHR launch: le dice al servidor de autorización que hay un
 * contexto que resolver. En el lanzamiento autónomo no se pide, porque no hay contexto que pedir.
 */
export const SCOPES_DEL_PROFESIONAL = new InjectionToken<string>('scopes', {
  providedIn: 'root',
  factory: () =>
    'openid fhirUser user/*.rs user/Patient.c user/Practitioner.c user/ServiceRequest.c',
});
