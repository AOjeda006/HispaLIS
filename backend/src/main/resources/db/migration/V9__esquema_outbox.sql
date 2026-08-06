-- El `outbox` transaccional: los hechos que el laboratorio deja apuntados para publicar (§9).
--
-- Va en un esquema aparte y no en `dominio` porque no es dominio: es la bandeja de salida hacia el
-- exterior. Lo que sí comparte con `dominio` es la BASE DE DATOS, y eso no es un detalle de montaje
-- — es el patrón entero. Escribir el hecho en la misma transacción que el agregado es lo que impide
-- las dos averías del bus:
--
--   * publicar algo que la transacción acabó revirtiendo (el bus anuncia un resultado que no existe);
--   * perder un hecho porque el broker estaba caído justo cuando se confirmó la escritura.
--
-- El relay que lea esta tabla y la publique en Kafka es del hito 2 (ítem 30). Aquí solo se escribe.

CREATE SCHEMA IF NOT EXISTS outbox;

CREATE TABLE outbox.hecho (
    id                  uuid        PRIMARY KEY,

    -- El nombre del `TipoDeHecho`. Texto y no `enum` de PostgreSQL: añadir un tipo nuevo no debe
    -- exigir una migración, y el conjunto de valores lo cierra el dominio, que es donde vive la regla.
    tipo                text        NOT NULL,

    -- El paciente. Es la clave de partición del bus: así todo lo de una persona se consume en el
    -- orden en que ocurrió, que es lo único que hace falta para no aplicar una validación antes que
    -- el resultado que valida.
    clave_de_particion  uuid        NOT NULL,

    -- Referencias, NUNCA PHI. La forma de cada valor la exige el agregado `Hecho`; aquí abajo se
    -- guarda como `jsonb` para que el día que haya que auditar qué se publicó se pueda consultar
    -- por campo en vez de con `LIKE` sobre una cadena.
    carga               jsonb       NOT NULL,

    creado_en           timestamptz NOT NULL,

    -- NULL = pendiente. Lo sella el relay cuando el hecho llega al bus.
    publicado_en        timestamptz
);

-- El relay pregunta siempre lo mismo: qué queda por publicar, en orden. El índice es PARCIAL a
-- propósito: lo publicado no se vuelve a consultar y no tiene por qué ocupar el índice ni encarecer
-- cada inserción.
CREATE INDEX hecho_pendiente ON outbox.hecho (creado_en) WHERE publicado_en IS NULL;

COMMENT ON TABLE  outbox.hecho                     IS 'Bandeja de salida: hechos escritos en la transacción del dominio, pendientes de publicar.';
COMMENT ON COLUMN outbox.hecho.carga               IS 'Solo referencias e identidades. Un nombre, un NHC o un DNI aquí es una fuga de PHI.';
COMMENT ON COLUMN outbox.hecho.clave_de_particion  IS 'pacienteId. Clave de reparto en el bus.';
