import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { porIdentificador, SID_COLEGIADO, SID_DNI_NIE, SID_NHC } from './sistemas';

/**
 * Cruza los `system` de la web contra la tabla que los define.
 *
 * `ig/input/fsh/aliases.fsh` es la fuente de verdad de los `Identifier.system` del proyecto, y esta
 * web tiene por fuerza su propia copia: el navegador no lee FSH. La copia no es el problema; el
 * problema sería que se separasen sin que nadie se entere, porque un `system` equivocado no rompe
 * nada de forma visible — simplemente deja de encontrar pacientes, o encuentra al que no era.
 */

const ALIASES = join(__dirname, '..', '..', '..', '..', 'ig', 'input', 'fsh', 'aliases.fsh');

function aliasDeclarado(nombre: string): string {
  const fsh = readFileSync(ALIASES, 'utf8');
  const declaracion = new RegExp(`^Alias:\\s+\\$${nombre}\\s*=\\s*(\\S+)\\s*$`, 'm').exec(fsh);

  expect(declaracion, `«$${nombre}» ya no está declarado en aliases.fsh`).not.toBeNull();
  return declaracion![1];
}

describe('los system de identificador', () => {
  it('el del NHC es el que declara la guía', () => {
    expect(SID_NHC).toBe(aliasDeclarado('SID_NHC'));
  });

  it('el del DNI/NIE es el OID que adoptó el proyecto del Ministerio', () => {
    expect(SID_DNI_NIE).toBe(aliasDeclarado('SID_DNI_NIE'));
  });

  it('el del colegiado es el genérico que declara la guía', () => {
    expect(SID_COLEGIADO).toBe(aliasDeclarado('SID_COLEGIADO'));
  });

  it('el criterio de búsqueda lleva el system delante del valor', () => {
    // Buscar «00000042» a secas encontraría a cualquiera cuyo DNI fuese ese número.
    expect(porIdentificador(SID_NHC, '00000042')).toBe(
      'https://aojeda006.github.io/HispaLIS/sid/nhc|00000042',
    );
  });
});
