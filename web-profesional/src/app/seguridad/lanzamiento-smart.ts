import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import {
  CLIENTE_ID,
  ISS_DE_CONFIANZA,
  ISS_POR_DEFECTO,
  REDIRECCION,
  SCOPES_DEL_PROFESIONAL,
} from './configuracion-smart';
import { retoDe, valorAleatorio } from './pkce';
import { Sesion } from './sesion';

/**
 * El lanzamiento SMART, de principio a fin.
 *
 * **EHR launch** es esto: el sistema clínico abre esta web en `/launch?iss=…&launch=…`. El `iss` dice
 * contra qué servidor FHIR se va a trabajar y el `launch` es un valor opaco que representa el
 * contexto —qué paciente tiene abierto el profesional, en qué episodio—. La aplicación **no lo
 * interpreta**: lo devuelve tal cual al servidor de autorización, que es quien sabe traducirlo, y el
 * contexto vuelve resuelto dentro del testigo.
 *
 * **Nada se cablea.** De la URL base de FHIR se descubre `.well-known/smart-configuration`, y de ahí
 * salen el `authorization_endpoint` y el `token_endpoint`. Escribirlos aquí convertiría un cambio de
 * configuración del servidor de identidad en un error en ejecución de esta web.
 *
 * **Lo que sí se comprueba: el `iss`.** Llega por la URL y decide a dónde se manda al usuario a
 * identificarse. Aceptar cualquiera es la vulnerabilidad clásica de este flujo.
 */

/** Lo que se guarda entre la ida y la vuelta. Vive lo que dura la redirección. */
const CLAVE_EN_VUELO = 'hispalis.lanzamiento';

interface EnVuelo {
  readonly estado: string;
  readonly verificador: string;
  readonly puntoDeTestigo: string;
  readonly iss: string;
  /** A dónde ir cuando termine el lanzamiento. */
  readonly destino: string;
}

/** Lo que publica el servidor en su `.well-known/smart-configuration`. */
interface ConfiguracionDelServidor {
  readonly authorization_endpoint: string;
  readonly token_endpoint: string;
  readonly code_challenge_methods_supported?: readonly string[];
}

/** La respuesta del canje. */
interface Concesion {
  readonly access_token: string;
  readonly expires_in?: number;
  readonly scope?: string;
  readonly patient?: string;
  readonly id_token?: string;
  readonly fhirUser?: string;
}

/** Un lanzamiento que no se puede completar, con el motivo escrito para una persona. */
export class ErrorDeLanzamiento extends Error {
  constructor(mensaje: string) {
    super(mensaje);
    this.name = 'ErrorDeLanzamiento';
  }
}

@Injectable({ providedIn: 'root' })
export class LanzamientoSmart {
  private readonly http = inject(HttpClient);
  private readonly sesion = inject(Sesion);
  private readonly clienteId = inject(CLIENTE_ID);
  private readonly redireccion = inject(REDIRECCION);
  private readonly scopes = inject(SCOPES_DEL_PROFESIONAL);
  private readonly issPorDefecto = inject(ISS_POR_DEFECTO);
  private readonly deConfianza = inject(ISS_DE_CONFIANZA);

  /**
   * Arranca el lanzamiento y devuelve a dónde hay que mandar el navegador.
   *
   * Devuelve la URL en vez de navegar para que se pueda probar: quién ejecuta la redirección es el
   * componente, que es quien tiene derecho a tocar `location`.
   *
   * @param iss la base FHIR que dijo el lanzador; sin ella, el lanzamiento es autónomo
   * @param launch el identificador opaco de contexto, solo en EHR launch
   * @param destino a dónde ir al terminar
   */
  async comenzar(iss: string | null, launch: string | null, destino: string): Promise<string> {
    const servidor = this.comprobarElIss(iss);
    const configuracion = await this.descubrir(servidor);
    this.exigirS256(configuracion);

    const estado = valorAleatorio();
    const verificador = valorAleatorio();
    sessionStorage.setItem(
      CLAVE_EN_VUELO,
      JSON.stringify({
        estado,
        verificador,
        puntoDeTestigo: configuracion.token_endpoint,
        iss: servidor,
        destino,
      } satisfies EnVuelo),
    );

    let parametros = new HttpParams()
      .set('response_type', 'code')
      .set('client_id', this.clienteId)
      .set('redirect_uri', this.redireccion)
      // `aud` es obligatorio y no es ceremonia: es lo que le dice al servidor de autorización para qué
      // servidor de recursos se pide el testigo. Sin él, el testigo que salga valdría en cualquiera.
      .set('aud', servidor)
      .set('scope', launch ? `launch ${this.scopes}` : this.scopes)
      .set('state', estado)
      .set('code_challenge', await retoDe(verificador))
      .set('code_challenge_method', 'S256');

    if (launch) {
      parametros = parametros.set('launch', launch);
    }

    return `${configuracion.authorization_endpoint}?${parametros.toString()}`;
  }

  /**
   * Cierra el lanzamiento: canjea el código por un testigo y abre la sesión.
   *
   * @returns a dónde llevar al profesional
   */
  async terminar(
    codigo: string | null,
    estado: string | null,
    error: string | null,
  ): Promise<string> {
    const enVuelo = this.recogerLoQueQuedoEnVuelo();

    if (error) {
      // Lo que llega en `error` lo escribe el servidor de autorización, no el usuario. Se enseña
      // porque es lo único que explica un `access_denied` o un `invalid_scope`.
      throw new ErrorDeLanzamiento(
        `El servidor de autorización no ha concedido el acceso: ${error}.`,
      );
    }
    if (!codigo) {
      throw new ErrorDeLanzamiento('La vuelta del lanzamiento no trae código de autorización.');
    }
    // Comparación estricta y sin atajos: si el `state` no es exactamente el que se guardó, esta
    // respuesta no es de la petición que salió de aquí y no se canjea.
    if (!estado || estado !== enVuelo.estado) {
      throw new ErrorDeLanzamiento(
        'La respuesta del servidor de autorización no corresponde a este lanzamiento.',
      );
    }

    const cuerpo = new HttpParams()
      .set('grant_type', 'authorization_code')
      .set('code', codigo)
      .set('redirect_uri', this.redireccion)
      .set('client_id', this.clienteId)
      // El verificador que nunca salió del navegador. Es lo que hace que un código robado no sirva.
      .set('code_verifier', enVuelo.verificador);

    const concesion = await firstValueFrom(
      this.http.post<Concesion>(enVuelo.puntoDeTestigo, cuerpo.toString(), {
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          Accept: 'application/json',
        },
      }),
    );

    this.sesion.abrir({
      testigo: concesion.access_token,
      caducaEn: Date.now() + (concesion.expires_in ?? 300) * 1000,
      fhirUser: concesion.fhirUser ?? fhirUserDelIdToken(concesion.id_token),
      paciente: concesion.patient,
      // Lo que se guarda es lo CONCEDIDO, no lo pedido: un servidor puede recortar, y la web tiene
      // que enseñar lo que se puede hacer de verdad, no lo que se aspiraba a poder hacer.
      scopes: concesion.scope ?? '',
    });

    sessionStorage.removeItem(CLAVE_EN_VUELO);
    return enVuelo.destino;
  }

  private comprobarElIss(iss: string | null): string {
    const servidor = iss ?? this.issPorDefecto;
    if (!this.deConfianza.includes(servidor)) {
      throw new ErrorDeLanzamiento(
        `Este lanzamiento viene de un servidor que esta aplicación no reconoce: ${servidor}.`,
      );
    }
    return servidor;
  }

  private async descubrir(iss: string): Promise<ConfiguracionDelServidor> {
    const url = `${iss.replace(/\/+$/, '')}/.well-known/smart-configuration`;
    try {
      return await firstValueFrom(this.http.get<ConfiguracionDelServidor>(url));
    } catch {
      throw new ErrorDeLanzamiento(
        'No se ha podido leer la configuración SMART del laboratorio. Vuelve a intentarlo en un momento.',
      );
    }
  }

  /**
   * Si el servidor no ofrece `S256`, no se sigue.
   *
   * Caer a `plain` sería mandar el verificador en claro por la barra del navegador, que es
   * exactamente lo que PKCE existe para evitar. La norma dice que un servidor *NO DEBE* soportarlo;
   * el que no ofrezca `S256` es un servidor con el que esta aplicación no debe hablar.
   */
  private exigirS256(configuracion: ConfiguracionDelServidor): void {
    const metodos = configuracion.code_challenge_methods_supported;
    if (metodos && !metodos.includes('S256')) {
      throw new ErrorDeLanzamiento(
        'El servidor de autorización no ofrece PKCE con S256, y esta aplicación no se lanza sin él.',
      );
    }
  }

  private recogerLoQueQuedoEnVuelo(): EnVuelo {
    const guardado = sessionStorage.getItem(CLAVE_EN_VUELO);
    if (!guardado) {
      throw new ErrorDeLanzamiento(
        'No hay ningún lanzamiento en curso en esta pestaña. Vuelve a abrir la aplicación desde el sistema clínico.',
      );
    }
    // Se retira antes de usarlo: un código de autorización es de un solo uso, y dejar el verificador
    // ahí después de canjearlo solo sirve para que un segundo intento haga algo raro.
    sessionStorage.removeItem(CLAVE_EN_VUELO);
    return JSON.parse(guardado) as EnVuelo;
  }
}

/**
 * El `fhirUser` del `id_token`, cuando el servidor no lo pone en la respuesta del testigo.
 *
 * Se lee el cuerpo del JWT **sin verificar la firma, y a propósito**: aquí no se está tomando ninguna
 * decisión de seguridad con él —solo se enseña quién ha entrado—. Lo que decide qué datos salen es el
 * testigo, y ese lo comprueba el laboratorio. Verificar aquí una firma daría una falsa sensación de
 * rigor sin añadir ninguno.
 */
function fhirUserDelIdToken(idToken: string | undefined): string | undefined {
  if (!idToken) {
    return undefined;
  }
  try {
    const cuerpo = idToken.split('.')[1];
    const json = atob(cuerpo.replace(/-/g, '+').replace(/_/g, '/'));
    return (JSON.parse(json) as { fhirUser?: string }).fhirUser;
  } catch {
    return undefined;
  }
}
