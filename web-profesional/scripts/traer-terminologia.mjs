/**
 * Trae a `public/` el catálogo de pruebas que publica la guía de implementación.
 *
 * **Esto es el invariante D15 aplicado a la web.** La pantalla de alta de petición tiene que ofrecer
 * las pruebas que el laboratorio oferta, y ese catálogo ya existe: es el `CodeSystem` de la IG, el
 * mismo que consumen el backend y el generador de datos sintéticos. Escribir aquí una lista de
 * `{ codigo, nombre }` sería una lista paralela — la que el proyecto prohíbe—, y el día que se añada
 * una prueba al catálogo la web seguiría ofreciendo el de antes sin que nadie se entere.
 *
 * El artefacto **no está versionado**: lo produce SUSHI a partir del FSH. Si falta, esto para el
 * build y dice qué ejecutar, en vez de dejar una web que arranca con un desplegable vacío.
 *
 * Es el mismo trato que hace `simuladores/generador/terminologia.py`, incluida la variable de
 * entorno para apuntar a otro directorio (la CI la usa después de ejecutar SUSHI).
 */

import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Permite apuntar a otro directorio de recursos. */
const VARIABLE_ENTORNO = 'HISPALIS_TERMINOLOGIA';

const ARTEFACTOS = ['CodeSystem-catalogo-pruebas.json'];

/** Dentro de `public/`, para que se sirva tal cual y se pida con `HttpClient` como cualquier otro. */
const DESTINO = 'public/terminologia';

const raizDelComponente = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const origen =
  process.env[VARIABLE_ENTORNO] ?? resolve(raizDelComponente, '..', 'ig', 'fsh-generated', 'resources');

const destino = join(raizDelComponente, DESTINO);
mkdirSync(destino, { recursive: true });

for (const artefacto of ARTEFACTOS) {
  const fichero = join(origen, artefacto);
  if (!existsSync(fichero)) {
    console.error(
      `No se encuentra «${fichero}». La terminología no se copia a mano: se lee de lo que ` +
        `produce la guía. Ejecuta «npx fsh-sushi .» dentro de «ig/», o apunta a otro directorio ` +
        `con la variable de entorno ${VARIABLE_ENTORNO}.`
    );
    process.exit(1);
  }
  copyFileSync(fichero, join(destino, artefacto));
}
