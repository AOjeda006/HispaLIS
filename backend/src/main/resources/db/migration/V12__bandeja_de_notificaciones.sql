-- La bandeja de notificaciones de `Subscription` (ítem 44).
--
-- Es un `outbox` más, y por las mismas dos razones que el de Kafka (V9): la fila se escribe en la
-- MISMA transacción que la proyección que la provoca —así no se notifica un resultado que la
-- transacción acabó revirtiendo— y sobrevive a que el receptor esté caído. Lo que cambia respecto de
-- aquel es a dónde va: allí un broker que siempre acepta, aquí una llamada HTTP saliente a un
-- tercero, que falla de verdad y hay que saber contarlo.
--
-- Va en un esquema propio y no en `outbox` a propósito: `outbox.hecho` es lo que el laboratorio
-- cuenta de sí mismo, y esto es a quién se lo ha contado ya. Mezclarlos convertiría un hecho en N
-- filas según cuántos suscriptores hubiera ese día.
--
-- ⚠️ Aquí NO va el recurso, solo su referencia. Es el invariante 6 aplicado a la base de datos: si el
-- valor de la TSH estuviera en esta tabla, `content = id-only` sería una decisión de serialización y
-- no una garantía — bastaría un cambio de una línea para publicarlo.

CREATE SCHEMA IF NOT EXISTS notificacion;

CREATE TABLE notificacion.evento (
    id                uuid        PRIMARY KEY,

    -- El id lógico de la `Subscription`. Texto y sin clave ajena: la `Subscription` vive en el
    -- esquema de HAPI, que no es nuestro y no se referencia desde fuera (ADR-0002).
    suscripcion_id    text        NOT NULL,

    -- `SubscriptionStatus.notificationEvent.eventNumber`: correlativo POR SUSCRIPCIÓN y empezando en
    -- 1. Es lo que le permite al receptor saber que se ha perdido el 7 sin preguntar nada.
    numero            bigint      NOT NULL,

    -- Qué mirar. `Observation/<uuid>` y nada más.
    foco              text        NOT NULL,
    ocurrido_en       timestamptz NOT NULL,

    -- PENDIENTE | ENTREGADO | FALLIDO. Texto y no `enum`, como en `outbox.hecho`.
    estado            text        NOT NULL,
    intentos          integer     NOT NULL DEFAULT 0,

    -- El motivo del último fallo, que es lo que acaba saliendo por `$status` en
    -- `SubscriptionStatus.error`. Es un mensaje técnico del canal —«Connection refused»—, nunca
    -- clínico: aquí no hay nada del paciente que contar.
    ultimo_error      text,
    entregado_en      timestamptz,

    -- Dos notificaciones con el mismo número en la misma suscripción romperían la cuenta del
    -- receptor, que es lo único que tiene para detectar pérdidas.
    UNIQUE (suscripcion_id, numero)
);

-- El relay pregunta siempre lo mismo: qué queda por entregar, en orden. Índice PARCIAL, como el del
-- outbox: lo entregado no se vuelve a consultar por aquí.
CREATE INDEX evento_pendiente ON notificacion.evento (ocurrido_en) WHERE estado = 'PENDIENTE';

-- Y `$events` pregunta lo contrario: dame los de esta suscripción por número.
CREATE INDEX evento_por_suscripcion ON notificacion.evento (suscripcion_id, numero);

COMMENT ON TABLE  notificacion.evento              IS 'Notificaciones de Subscription pendientes de entregar, entregadas o fallidas.';
COMMENT ON COLUMN notificacion.evento.foco         IS 'Referencia al recurso. NUNCA su contenido: el canal es id-only (invariante 6).';
COMMENT ON COLUMN notificacion.evento.numero       IS 'eventNumber, correlativo por suscripción. Lo que permite al receptor detectar pérdidas.';
COMMENT ON COLUMN notificacion.evento.ultimo_error IS 'Motivo técnico del último intento fallido. Sale por $status en SubscriptionStatus.error.';
