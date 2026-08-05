import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';

import { BASE_FHIR } from './configuracion';
import { mensajeDeError, SIN_RESPUESTA } from './operation-outcome';
import { Bundle } from './tipos';

/**
 * El acceso a la API FHIR del laboratorio. Es el único sitio de la web que habla HTTP.
 *
 * Tres decisiones viven aquí y conviene no deshacerlas por costumbre:
 *
 * 1. **Se busca con `POST [tipo]/_search`, no con `GET [tipo]?…`.** Los criterios llevan el número
 *    de historia del paciente, y una URL con eso dentro se queda en la barra del navegador, en su
 *    historial, en el log del proxy y en la traza del servidor. FHIR previó el caso: los mismos
 *    criterios viajan en el cuerpo como formulario. El servidor admite las dos formas —hay un test
 *    del backend que lo comprueba—; la web usa esta.
 * 2. **La página siguiente se pide con la URL que devuelve el servidor, tal cual.** Es opaca: lleva
 *    el identificador de la búsqueda cacheada, no un desplazamiento calculable. Un cliente que se
 *    invente `&_getpagesoffset=…` funciona hasta que el servidor cambia de estrategia, y entonces se
 *    salta resultados sin avisar.
 * 3. **Los fallos salen traducidos, nunca crudos.** Lo que llega es un `OperationOutcome` con el
 *    motivo escrito en español; quien llama recibe un {@link ErrorDelLaboratorio} con ese texto ya
 *    listo para enseñar.
 */

/** Lo que aceptamos y lo que enviamos: el tipo de medio de FHIR, no `application/json` a secas. */
const TIPO_FHIR = 'application/fhir+json';

/** Un fallo de la API, ya traducido a algo que se le puede enseñar a una persona. */
export class ErrorDelLaboratorio extends Error {
  /**
   * @param mensaje el motivo, en español, tal y como lo explicó el servidor
   * @param estado el código HTTP, o `0` si no llegó a haber respuesta
   */
  constructor(
    mensaje: string,
    readonly estado: number,
  ) {
    super(mensaje);
    this.name = 'ErrorDelLaboratorio';
  }
}

/** Una página de resultados de búsqueda, con lo que hace falta para seguir leyendo. */
export interface Pagina<T> {
  readonly recursos: readonly T[];
  /** Cuántos hay en total, según el servidor. */
  readonly total?: number;
  /** URL de la página siguiente, **opaca**, o indefinido si esta es la última. */
  readonly siguiente?: string;
}

/** Criterios de búsqueda, tal y como los nombra FHIR (`identifier`, `subject`, `_count`…). */
export type Criterios = Record<string, string | number>;

/** Lo mínimo que tiene cualquier recurso de FHIR. */
interface Recurso {
  readonly resourceType: string;
}

@Injectable({ providedIn: 'root' })
export class ClienteFhir {
  private readonly http = inject(HttpClient);
  private readonly base = inject(BASE_FHIR);

  /**
   * Lee un recurso.
   *
   * @param referencia la referencia relativa, `Patient/<id>`
   */
  leer<T extends Recurso>(referencia: string): Promise<T> {
    return this.pedir(
      this.http.get<T>(`${this.base}/${referencia}`, { headers: { Accept: TIPO_FHIR } }),
    );
  }

  /**
   * Da de alta un recurso y devuelve **lo que el servidor publicó**, no lo que se le mandó.
   *
   * La diferencia importa: el servidor asigna el id, la versión y la narrativa, y puede normalizar
   * lo recibido. Enseñar de vuelta el objeto enviado haría creer que se guardó tal cual.
   *
   * Se pide con `Prefer: return=representation` para ahorrar un viaje, pero eso es una preferencia
   * y no una obligación: si el servidor contesta sin cuerpo, se lee del `Location` que sí devuelve
   * siempre. Ese segundo `GET` es además el *read-your-writes* del §9 ejercitado de verdad.
   */
  async crear<T extends Recurso>(recurso: T): Promise<T> {
    const respuesta = await this.pedir(
      this.http.post<T>(`${this.base}/${recurso.resourceType}`, recurso, {
        headers: { 'Content-Type': TIPO_FHIR, Accept: TIPO_FHIR, Prefer: 'return=representation' },
        observe: 'response',
      }),
    );

    const publicado = respuesta.body;
    if (publicado?.resourceType === recurso.resourceType) {
      return publicado;
    }

    const ubicacion = respuesta.headers.get('Location');
    if (!ubicacion) {
      throw new ErrorDelLaboratorio(
        'El laboratorio ha aceptado el alta pero no ha dicho dónde queda guardada.',
        respuesta.status,
      );
    }
    return this.leer<T>(referenciaDe(ubicacion));
  }

  /**
   * Busca recursos de un tipo. Los criterios viajan en el cuerpo, no en la URL.
   *
   * @param tipo el tipo de recurso, `Patient`, `DiagnosticReport`…
   * @param criterios los parámetros de búsqueda de FHIR
   */
  buscar<T extends Recurso>(tipo: string, criterios: Criterios): Promise<Pagina<T>> {
    const formulario = Object.entries(criterios).reduce(
      (parametros, [nombre, valor]) => parametros.set(nombre, String(valor)),
      new HttpParams(),
    );

    return this.pedir(
      this.http.post<Bundle<T>>(`${this.base}/${tipo}/_search`, formulario.toString(), {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: TIPO_FHIR },
      }),
    ).then(enPaginas);
  }

  /**
   * Pide la página siguiente **por el enlace que dio el servidor**.
   *
   * @throws Error si la página no tiene siguiente; compruébalo antes con {@link Pagina.siguiente}
   */
  siguiente<T extends Recurso>(pagina: Pagina<T>): Promise<Pagina<T>> {
    if (!pagina.siguiente) {
      throw new Error('Esta página es la última: el servidor no ha dado enlace a otra.');
    }
    return this.pedir(
      this.http.get<Bundle<T>>(pagina.siguiente, { headers: { Accept: TIPO_FHIR } }),
    ).then(enPaginas);
  }

  /** Recorre todas las páginas siguiendo los enlaces, y devuelve lo que haya en todas ellas. */
  async todo<T extends Recurso>(tipo: string, criterios: Criterios): Promise<readonly T[]> {
    const recogidos: T[] = [];
    let pagina = await this.buscar<T>(tipo, criterios);
    for (;;) {
      recogidos.push(...pagina.recursos);
      if (!pagina.siguiente) {
        return recogidos;
      }
      pagina = await this.siguiente(pagina);
    }
  }

  private async pedir<T>(peticion: Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(peticion);
    } catch (fallo) {
      throw traducir(fallo);
    }
  }
}

function enPaginas<T>(bundle: Bundle<T>): Pagina<T> {
  return {
    recursos: (bundle.entry ?? [])
      .map((entrada) => entrada.resource)
      .filter((recurso): recurso is T => recurso !== undefined),
    total: bundle.total,
    siguiente: (bundle.link ?? []).find((enlace) => enlace.relation === 'next')?.url,
  };
}

/**
 * De la cabecera `Location` a la referencia relativa.
 *
 * Llega como `http://…/fhir/Patient/<id>/_history/1` y lo que hace falta es `Patient/<id>`. No es
 * construir una URL a mano: es leer la que ya dio el servidor.
 */
function referenciaDe(ubicacion: string): string {
  const sinVersion = ubicacion.split('/_history/')[0];
  return sinVersion
    .split('/')
    .filter((parte) => parte.length > 0)
    .slice(-2)
    .join('/');
}

function traducir(fallo: unknown): ErrorDelLaboratorio {
  if (fallo instanceof HttpErrorResponse) {
    return new ErrorDelLaboratorio(mensajeDeError(fallo.error), fallo.status);
  }
  return new ErrorDelLaboratorio(SIN_RESPUESTA, 0);
}
