import { computed, Injectable, signal } from '@angular/core';

/**
 * La sesión del profesional: el testigo con el que se llama a la API y quién es quien llama.
 *
 * **Dónde se guarda y por qué.** En `sessionStorage`, no en una `cookie`. Una cookie la manda el
 * navegador sola en cada petición al origen, incluidas las que provoque otra pestaña o un formulario
 * de otro sitio — eso es CSRF, y evitarlo exigiría montar la defensa entera contra algo que aquí no
 * hace falta: el testigo lo pone esta aplicación a mano, en la cabecera, solo en las llamadas que
 * hace ella. Además, `sessionStorage` muere al cerrar la pestaña, que es justo la vida que debe tener
 * la sesión de un puesto de trabajo compartido.
 *
 * **Lo que esto no resuelve, y conviene decirlo:** con XSS, el testigo es legible. La respuesta a eso
 * no es esconderlo mejor —una cookie `httpOnly` tampoco lo salva, porque el atacante haría las
 * peticiones desde la propia página—, es que no haya XSS: Angular escapa por defecto y aquí no se usa
 * `innerHTML` en ningún sitio.
 *
 * **Y lo que nunca se guarda:** ni un dato clínico. Aquí solo hay el testigo, cuándo caduca y las dos
 * referencias que trae el contexto de lanzamiento.
 */

/** La clave de `sessionStorage`. Con prefijo para no chocar con nada de otra aplicación del origen. */
const CLAVE = 'hispalis.sesion';

/** Lo que se conserva de un lanzamiento. */
export interface DatosDeSesion {
  readonly testigo: string;
  /** Cuándo caduca, en milisegundos desde época. */
  readonly caducaEn: number;
  /** El recurso que representa al usuario, `Practitioner/<id>`, si el servidor lo dijo. */
  readonly fhirUser?: string;
  /** El paciente del contexto de lanzamiento, si lo hubo. */
  readonly paciente?: string;
  /** Los `scope` concedidos, que pueden no ser los que se pidieron. */
  readonly scopes: string;
}

@Injectable({ providedIn: 'root' })
export class Sesion {
  private readonly datos = signal<DatosDeSesion | undefined>(leerDeLaPestana());

  /** ¿Hay sesión utilizable ahora mismo? */
  readonly activa = computed(() => {
    const actual = this.datos();
    return actual !== undefined && actual.caducaEn > Date.now();
  });

  /** El recurso del usuario identificado, para enseñar quién ha entrado. */
  readonly fhirUser = computed(() => this.datos()?.fhirUser);

  /** Los `scope` que el servidor concedió de verdad. */
  readonly scopes = computed(() => this.datos()?.scopes ?? '');

  /** El testigo con el que firmar la siguiente llamada, o indefinido si no hay sesión válida. */
  testigo(): string | undefined {
    return this.activa() ? this.datos()?.testigo : undefined;
  }

  abrir(datos: DatosDeSesion): void {
    this.datos.set(datos);
    sessionStorage.setItem(CLAVE, JSON.stringify(datos));
  }

  cerrar(): void {
    this.datos.set(undefined);
    sessionStorage.removeItem(CLAVE);
  }
}

/**
 * Recupera la sesión al recargar la página.
 *
 * Sin esto, un F5 mandaría al profesional a volver a identificarse en mitad de un alta. Lo que se
 * lee es lo que escribió esta misma aplicación, y si está corrupto se descarta sin ruido: una sesión
 * ilegible es una sesión que no hay.
 */
function leerDeLaPestana(): DatosDeSesion | undefined {
  const guardado = sessionStorage.getItem(CLAVE);
  if (!guardado) {
    return undefined;
  }
  try {
    const datos = JSON.parse(guardado) as DatosDeSesion;
    return typeof datos.testigo === 'string' && typeof datos.caducaEn === 'number'
      ? datos
      : undefined;
  } catch {
    return undefined;
  }
}
