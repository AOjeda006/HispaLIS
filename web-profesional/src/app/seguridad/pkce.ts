/**
 * PKCE y el `state`: los dos valores que impiden que a esta web le roben la sesión.
 *
 * Esta aplicación es un **cliente público**. No tiene `client_secret` y no puede tenerlo: todo lo que
 * viaja en el paquete que se descarga el navegador lo puede leer cualquiera, así que un secreto ahí
 * no es un secreto. Lo que la protege son estas dos cosas:
 *
 * - **PKCE** (RFC 7636). El código de autorización viaja por la barra del navegador y puede
 *   interceptarlo otra aplicación del mismo dispositivo. Con PKCE, ese código no vale de nada sin el
 *   `code_verifier`, que nunca salió de aquí. **Solo `S256`**: la norma de SMART dice que un servidor
 *   *NO DEBE* soportar `plain`, y un cliente que lo usara estaría mandando el verificador en claro.
 * - **`state`**. Es lo que ata la respuesta a la petición que la provocó. Sin él, cualquiera puede
 *   mandarle al navegador un `?code=…` suyo y la aplicación se autenticaría con la sesión de otro.
 *   La norma pide **al menos 122 bits** de entropía; aquí van 256.
 *
 * Todo se genera con `crypto.getRandomValues`, que es el generador criptográfico del navegador.
 * `Math.random()` no vale para esto: es predecible y no lo esconde.
 */

/** 32 bytes = 256 bits, muy por encima de los 122 que exige la norma para el `state`. */
const BYTES_DE_ENTROPIA = 32;

/** Un valor aleatorio en base64url, para el `state` o para el `code_verifier`. */
export function valorAleatorio(): string {
  const bytes = new Uint8Array(BYTES_DE_ENTROPIA);
  crypto.getRandomValues(bytes);
  return base64url(bytes);
}

/**
 * El `code_challenge` de un verificador: su SHA-256 en base64url.
 *
 * Es una función de un solo sentido, y ahí está el truco: lo que viaja en la redirección al servidor
 * de autorización es el reto, y quien lo intercepte no puede deducir el verificador con el que
 * después se canjea el código.
 */
export async function retoDe(verificador: string): Promise<string> {
  const resumen = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verificador));
  return base64url(new Uint8Array(resumen));
}

/**
 * Base64 **url-safe y sin relleno**, que es lo que exige la especificación.
 *
 * El base64 de toda la vida usa `+`, `/` y `=`, y los tres tienen significado dentro de una URL. Un
 * reto codificado con el base64 normal falla de forma intermitente: solo cuando al resumen le toca
 * uno de esos caracteres.
 */
function base64url(bytes: Uint8Array): string {
  let binario = '';
  for (const byte of bytes) {
    binario += String.fromCharCode(byte);
  }
  return btoa(binario).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
