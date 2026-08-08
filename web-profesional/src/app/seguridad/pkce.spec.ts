import { describe, expect, it } from 'vitest';

import { retoDe, valorAleatorio } from './pkce';

describe('PKCE', () => {
  it('genera valores distintos cada vez, largos y en base64url', () => {
    const valores = new Set(Array.from({ length: 50 }, () => valorAleatorio()));

    expect(valores.size).toBe(50);
    // 43 caracteres de base64url son 256 bits: la norma pide 122 como mínimo para el `state`, y el
    // `code_verifier` tiene que estar entre 43 y 128 caracteres.
    valores.forEach((valor) => expect(valor).toMatch(/^[A-Za-z0-9_-]{43}$/));
  });

  it('el reto es el SHA-256 del verificador, en base64url y sin relleno', async () => {
    // El vector del apéndice B del RFC 7636: si esto cambia, es que se ha roto la interoperabilidad
    // con cualquier servidor de autorización, y el fallo aparecería como un `invalid_grant` opaco.
    const reto = await retoDe('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk');

    expect(reto).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('no deja escapar ni `+`, ni `/`, ni `=`', async () => {
    // El base64 de toda la vida usa los tres, y los tres significan algo dentro de una URL. Con el
    // codificador equivocado el fallo es intermitente: solo cuando al resumen le toca uno de ellos.
    const retos = await Promise.all(
      Array.from({ length: 30 }, () => valorAleatorio()).map((verificador) => retoDe(verificador)),
    );

    retos.forEach((reto) => expect(reto).toMatch(/^[A-Za-z0-9_-]+$/));
  });
});
