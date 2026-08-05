import { describe, expect, it } from 'vitest';

import { mensajeDeError, SIN_DETALLE, SIN_RESPUESTA } from './operation-outcome';

describe('el mensaje de error que ve el usuario', () => {
  it('sale del OperationOutcome y no de una tabla de códigos HTTP', () => {
    // El servidor ya explicó qué pasó, en español y en términos del negocio. Sustituirlo por un
    // «no se ha podido guardar» genérico tira la única explicación que sabe de qué va.
    const motivo =
      'La muestra A20000004 fue rechazada (HEM), así que no puede producir resultados.';

    expect(
      mensajeDeError({
        resourceType: 'OperationOutcome',
        issue: [{ severity: 'error', code: 'business-rule', diagnostics: motivo }],
      }),
    ).toBe(motivo);
  });

  it('quita el prefijo interno de HAPI, que al usuario no le dice nada', () => {
    expect(
      mensajeDeError({
        resourceType: 'OperationOutcome',
        issue: [
          {
            severity: 'error',
            code: 'conflict',
            diagnostics: 'HAPI-0550: HAPI-0989: El paciente ya está registrado',
          },
        ],
      }),
    ).toBe('El paciente ya está registrado.');
  });

  it('prefiere el texto de details cuando lo hay', () => {
    expect(
      mensajeDeError({
        resourceType: 'OperationOutcome',
        issue: [
          {
            severity: 'error',
            code: 'invalid',
            diagnostics: 'lo de dentro',
            details: { text: 'El número de historia clínica son ocho dígitos.' },
          },
        ],
      }),
    ).toBe('El número de historia clínica son ocho dígitos.');
  });

  it('ignora los avisos y se queda con los errores', () => {
    expect(
      mensajeDeError({
        resourceType: 'OperationOutcome',
        issue: [
          { severity: 'warning', code: 'informational', diagnostics: 'Falta la narrativa' },
          { severity: 'error', code: 'invalid', diagnostics: 'Falta el NHC' },
        ],
      }),
    ).toBe('Falta el NHC.');
  });

  it('junta varios errores en un solo mensaje', () => {
    expect(
      mensajeDeError({
        resourceType: 'OperationOutcome',
        issue: [
          { severity: 'error', code: 'invalid', diagnostics: 'Falta el NHC' },
          { severity: 'error', code: 'invalid', diagnostics: 'Falta el sexo' },
        ],
      }),
    ).toBe('Falta el NHC. Falta el sexo.');
  });

  it('no enseña HTML de un proxy como si fuese el motivo del rechazo', () => {
    // Un portal cautivo o un proxy de empresa devuelven HTML con la cabecera de la petición
    // original. Fiarse del Content-Type pondría un trozo de página web en el aviso de error.
    expect(mensajeDeError('<html><body>403 Forbidden</body></html>')).toBe(SIN_RESPUESTA);
  });

  it.each([null, undefined, {}, { resourceType: 'Patient' }])(
    'lo que no es un OperationOutcome se trata como si no hubiera respuesta: %s',
    (cuerpo) => {
      expect(mensajeDeError(cuerpo)).toBe(SIN_RESPUESTA);
    },
  );

  it('un OperationOutcome sin motivos aprovechables lo dice, no se queda callado', () => {
    // Un aviso vacío es peor que uno genérico: el usuario ve que no pasó nada y vuelve a darle.
    expect(
      mensajeDeError({
        resourceType: 'OperationOutcome',
        issue: [{ severity: 'error', code: 'processing' }],
      }),
    ).toBe(SIN_DETALLE);
  });
});
