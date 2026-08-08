import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ISS_DE_CONFIANZA, ISS_POR_DEFECTO, REDIRECCION } from './configuracion-smart';
import { ErrorDeLanzamiento, LanzamientoSmart } from './lanzamiento-smart';
import { Sesion } from './sesion';

const ISS = 'https://laboratorio.pruebas/fhir';
const AUTORIZACION = 'https://identidad.pruebas/realms/hispalis/protocol/openid-connect/auth';
const TESTIGO = 'https://identidad.pruebas/realms/hispalis/protocol/openid-connect/token';

const DESCUBRIMIENTO = {
  issuer: 'https://identidad.pruebas/realms/hispalis',
  authorization_endpoint: AUTORIZACION,
  token_endpoint: TESTIGO,
  code_challenge_methods_supported: ['S256'],
};

describe('el lanzamiento SMART', () => {
  let smart: LanzamientoSmart;
  let sesion: Sesion;
  let servidor: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: REDIRECCION, useValue: 'https://web.pruebas/callback' },
        { provide: ISS_POR_DEFECTO, useValue: ISS },
        { provide: ISS_DE_CONFIANZA, useValue: [ISS] },
      ],
    });
    smart = TestBed.inject(LanzamientoSmart);
    sesion = TestBed.inject(Sesion);
    servidor = TestBed.inject(HttpTestingController);
  });

  afterEach(() => servidor.verify());

  /** Arranca un lanzamiento y contesta el descubrimiento, que es el primer viaje de todos. */
  async function comenzar(iss: string | null, launch: string | null): Promise<URL> {
    const enCurso = smart.comenzar(iss, launch, '/informes');
    await Promise.resolve();
    servidor.expectOne(`${ISS}/.well-known/smart-configuration`).flush(DESCUBRIMIENTO);
    return new URL(await enCurso);
  }

  describe('la ida', () => {
    it('manda al authorization_endpoint que descubrió, con PKCE S256 y el contexto de lanzamiento', async () => {
      const destino = await comenzar(ISS, 'ctx-abc123');

      expect(`${destino.origin}${destino.pathname}`).toBe(AUTORIZACION);
      const parametros = destino.searchParams;
      expect(parametros.get('response_type')).toBe('code');
      expect(parametros.get('client_id')).toBe('hispalis-web');
      expect(parametros.get('redirect_uri')).toBe('https://web.pruebas/callback');
      expect(parametros.get('code_challenge_method')).toBe('S256');
      expect(parametros.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
      expect(parametros.get('launch')).toBe('ctx-abc123');
    });

    it('pide `aud`, que es lo que ata el testigo a este servidor de recursos', async () => {
      // Sin `aud`, el testigo que salga valdría en cualquier servidor del mismo realm.
      const destino = await comenzar(ISS, 'ctx-abc123');

      expect(destino.searchParams.get('aud')).toBe(ISS);
    });

    it('pide `launch` y `user/*.rs`, y también los `.c` que el alta necesita', async () => {
      // `user/*.rs` es solo lectura: con eso a secas, la pantalla de alta contestaría 403 al guardar.
      const scopes = (await comenzar(ISS, 'ctx-abc123')).searchParams.get('scope')?.split(' ');

      expect(scopes).toContain('launch');
      expect(scopes).toContain('user/*.rs');
      expect(scopes).toContain('user/ServiceRequest.c');
      expect(scopes).not.toContain('user/*.cruds');
    });

    it('en lanzamiento autónomo no pide `launch`: no hay contexto que resolver', async () => {
      const destino = await comenzar(null, null);

      expect(destino.searchParams.has('launch')).toBe(false);
      expect(destino.searchParams.get('scope')?.split(' ')).not.toContain('launch');
    });

    it('el `state` es distinto en cada lanzamiento y largo de verdad', async () => {
      const primero = (await comenzar(ISS, null)).searchParams.get('state') ?? '';
      const segundo = (await comenzar(ISS, null)).searchParams.get('state') ?? '';

      expect(primero).not.toBe(segundo);
      // 43 caracteres de base64url son 256 bits, muy por encima de los 122 que exige la norma.
      expect(primero).toMatch(/^[A-Za-z0-9_-]{43}$/);
    });

    it('no se lanza contra un `iss` que no reconoce', async () => {
      // Es la vulnerabilidad clásica del EHR launch: el `iss` llega por la URL y decide a dónde se
      // manda al usuario a identificarse. Un enlace con el `iss` del atacante bastaría.
      await expect(smart.comenzar('https://hospital-del-atacante/fhir', 'x', '/')).rejects.toThrow(
        ErrorDeLanzamiento,
      );
    });

    it('no se lanza contra un servidor que no ofrece S256', async () => {
      const enCurso = smart.comenzar(ISS, null, '/');
      await Promise.resolve();
      servidor
        .expectOne(`${ISS}/.well-known/smart-configuration`)
        .flush({ ...DESCUBRIMIENTO, code_challenge_methods_supported: ['plain'] });

      await expect(enCurso).rejects.toThrow(/S256/);
    });
  });

  describe('la vuelta', () => {
    /** Un lanzamiento completo hasta tener el `state` que el servidor va a devolver. */
    async function estadoEnVuelo(): Promise<string> {
      return (await comenzar(ISS, 'ctx-abc123')).searchParams.get('state') ?? '';
    }

    it('canja el código con el verificador y abre la sesión', async () => {
      const estado = await estadoEnVuelo();

      const terminando = smart.terminar('codigo-123', estado, null);
      await Promise.resolve();
      const canje = servidor.expectOne(TESTIGO);
      expect(canje.request.body).toContain('grant_type=authorization_code');
      expect(canje.request.body).toContain('code=codigo-123');
      expect(canje.request.body).toContain('code_verifier=');
      // Un cliente público no tiene secreto, y mandar uno desde el navegador sería regalarlo.
      expect(canje.request.body).not.toContain('client_secret');
      canje.flush({
        access_token: 'testigo-de-la-dra-alvarez',
        expires_in: 900,
        scope: 'openid fhirUser user/*.rs',
        fhirUser: 'Practitioner/dra-alvarez',
      });

      await expect(terminando).resolves.toBe('/informes');
      expect(sesion.activa()).toBe(true);
      expect(sesion.testigo()).toBe('testigo-de-la-dra-alvarez');
      expect(sesion.fhirUser()).toBe('Practitioner/dra-alvarez');
    });

    it('guarda los scopes CONCEDIDOS, no los pedidos', async () => {
      // Un servidor puede recortar. La web tiene que enseñar lo que se puede hacer de verdad.
      const estado = await estadoEnVuelo();

      const terminando = smart.terminar('codigo-123', estado, null);
      await Promise.resolve();
      servidor
        .expectOne(TESTIGO)
        .flush({ access_token: 't', expires_in: 900, scope: 'openid user/*.rs' });
      await terminando;

      expect(sesion.scopes()).toBe('openid user/*.rs');
      expect(sesion.scopes()).not.toContain('user/ServiceRequest.c');
    });

    it('rechaza una vuelta con otro `state`: no es la respuesta a lo que salió de aquí', async () => {
      await estadoEnVuelo();

      await expect(smart.terminar('codigo-123', 'un-state-cualquiera', null)).rejects.toThrow(
        ErrorDeLanzamiento,
      );
      expect(sesion.activa()).toBe(false);
    });

    it('rechaza una vuelta sin `state`', async () => {
      await estadoEnVuelo();

      await expect(smart.terminar('codigo-123', null, null)).rejects.toThrow(ErrorDeLanzamiento);
    });

    it('rechaza una vuelta que no corresponde a ningún lanzamiento de esta pestaña', async () => {
      await expect(smart.terminar('codigo-123', 'lo-que-sea', null)).rejects.toThrow(
        ErrorDeLanzamiento,
      );
    });

    it('enseña el motivo cuando el servidor deniega', async () => {
      const estado = await estadoEnVuelo();

      await expect(smart.terminar(null, estado, 'access_denied')).rejects.toThrow(/access_denied/);
    });

    it('saca el fhirUser del id_token cuando no viene en la respuesta del testigo', async () => {
      const estado = await estadoEnVuelo();
      const idToken = `cabecera.${btoa(JSON.stringify({ fhirUser: 'Practitioner/dra-alvarez' }))}.firma`;

      const terminando = smart.terminar('codigo-123', estado, null);
      await Promise.resolve();
      servidor.expectOne(TESTIGO).flush({ access_token: 't', expires_in: 900, id_token: idToken });
      await terminando;

      expect(sesion.fhirUser()).toBe('Practitioner/dra-alvarez');
    });
  });
});
