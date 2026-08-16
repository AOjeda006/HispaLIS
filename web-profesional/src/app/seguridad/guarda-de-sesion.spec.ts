import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { guardaDeSesion } from './guarda-de-sesion';
import { Sesion } from './sesion';

/**
 * La guarda salió a **cero redondo** al medir la cobertura del cierre, y es camino real: corre en
 * cada navegación a una pantalla clínica (`adr-0041`, veredicto «camino real sin test»).
 *
 * Lo que se afirma no es que proteja datos —no protege, y su propio doc-comment lo dice—, sino las
 * dos cosas que sí promete: que sin sesión se lanza el flujo autónomo **conservando a dónde se
 * quería ir**, y que lo que viaja en ese parámetro es una ruta de la aplicación y no PHI.
 */
describe('la guarda de sesión', () => {
  let sesion: Sesion;

  /** `CanActivateFn` se ejecuta en contexto de inyección; el snapshot solo aporta la URL. */
  const alEntrarEn = (url: string): boolean | UrlTree =>
    TestBed.runInInjectionContext(() =>
      guardaDeSesion({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    ) as boolean | UrlTree;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({});
    sesion = TestBed.inject(Sesion);
  });

  it('deja pasar con la sesión abierta', () => {
    sesion.abrir({ testigo: 'vivo', caducaEn: Date.now() + 60_000, scopes: 'user/*.rs' });

    expect(alEntrarEn('/peticiones/alta')).toBe(true);
  });

  it('sin sesión manda al lanzamiento y se acuerda de a dónde se iba', () => {
    const destino = alEntrarEn('/informes/consulta');

    expect(destino).toBeInstanceOf(UrlTree);
    const arbol = destino as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(arbol)).toContain('/launch');
    expect(arbol.queryParams['destino']).toBe('/informes/consulta');
  });

  it('un testigo caducado no es una sesión', () => {
    sesion.abrir({ testigo: 'viejo', caducaEn: Date.now() - 1, scopes: 'user/*.rs' });

    expect(alEntrarEn('/peticiones/alta')).toBeInstanceOf(UrlTree);
  });

  it('el destino que se conserva es una ruta, nunca un criterio de búsqueda con PHI', () => {
    // La ruta la pone el enrutador y el número de historia viaja en el cuerpo de un
    // `POST [tipo]/_search`, nunca en la URL. Esto lo deja afirmado donde se podría romper.
    const arbol = alEntrarEn('/informes/consulta') as UrlTree;

    expect(arbol.queryParams['destino']).not.toMatch(/identifier=|nhc|[0-9]{8}/i);
  });
});
