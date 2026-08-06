-- El almacén de mensajes del motor: la bitácora de todo lo que entra por MLLP.
--
-- Vive en su PROPIO esquema y el motor no toca ningún otro. La regla de D5 —un solo camino de
-- escritura— no es una recomendación de estilo: el motor escribe en el laboratorio por la API FHIR,
-- como cualquier otro cliente, y este esquema es lo único suyo. Si algún día aparece aquí un
-- `SELECT` contra `dominio.*`, el invariante está roto.
--
-- Sobre el PHI: aquí SÍ hay datos de paciente, y tiene que haberlos. El mensaje original es la única
-- prueba de qué mandó el emisor, y el `nhc` indexado es lo que permite encontrarlo. La prohibición
-- del proyecto es PHI en URLs, logs, trazas y en el bus de eventos — no en el archivo del motor, que
-- es justamente donde el dato tiene que estar para poder auditarlo.

-- `IF NOT EXISTS` porque el propio Flyway lo crea antes de correr esto: su tabla de control vive
-- dentro (ver `spring.flyway.schemas`). Se deja escrito igualmente para que la migración se pueda
-- leer sola y para que aplicarla a mano contra una base limpia funcione.
CREATE SCHEMA IF NOT EXISTS integracion;

CREATE TABLE integracion.mensaje (
    id                   uuid        PRIMARY KEY,

    -- La clave de deduplicación. NO es `MSH-10` a secas: el estándar solo obliga a que el
    -- identificador de control sea único POR EMISOR. Dos analizadores que reinician su contador
    -- coinciden a la primera, y con `MSH-10` solo se descartarían mensajes buenos en silencio.
    aplicacion_emisora   text        NOT NULL,
    instalacion_emisora  text        NOT NULL,
    control_id           text        NOT NULL,

    tipo                 text        NOT NULL,
    evento               text        NOT NULL,
    -- El código de estructura de la tabla 0354 (`MSH-9-3`). Opcional: muchos emisores no lo mandan,
    -- y los que lo mandan a veces mandan uno que no existe. Ver `docs/adr/adr-0018-…`.
    estructura           text,
    version              text        NOT NULL,
    -- Lo declarado en `MSH-18`, tal cual. NULL = no venía. Se guarda para poder reproducir el
    -- problema el día que un nombre aparezca corrupto.
    charset_declarado    text,

    -- Metadatos indexables. El episodio es `PV1-19`.
    nhc                  text,
    episodio             text,

    recibido_en          timestamptz NOT NULL,
    -- El original ÍNTEGRO, ya decodificado al juego que declaraba pero por lo demás sin tocar.
    crudo                text        NOT NULL,

    estado               text        NOT NULL,
    detalle              text,
    procesado_en         timestamptz,

    CONSTRAINT mensaje_unico_por_emisor UNIQUE (aplicacion_emisora, instalacion_emisora, control_id),
    CONSTRAINT mensaje_estado_conocido CHECK (estado IN ('RECIBIDO', 'PROCESADO', 'RECHAZADO'))
);

-- «Enséñame todo lo de este paciente» es la primera pregunta que se hace cuando algo va mal.
CREATE INDEX mensaje_por_paciente ON integracion.mensaje (nhc) WHERE nhc IS NOT NULL;

-- Y la segunda es «qué ha fallado hoy».
CREATE INDEX mensaje_por_estado ON integracion.mensaje (estado, recibido_en DESC);

COMMENT ON TABLE  integracion.mensaje        IS 'Original íntegro de cada mensaje HL7 v2 recibido, con sus metadatos.';
COMMENT ON COLUMN integracion.mensaje.estado IS 'RECIBIDO = apuntado, aún sin aplicar · PROCESADO = aplicado en el laboratorio · RECHAZADO = no se aplicó, con el motivo en `detalle`.';
