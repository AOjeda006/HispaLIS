-- Los trabajos de exportación masiva y sus ficheros (ítem 49).
--
-- ⚠️ ¿Por qué persistir un trabajo asíncrono en vez de tenerlo en memoria? Porque los FICHEROS
-- sobreviven al reinicio y el trabajo en memoria no. Un backend que se cae a mitad de una exportación
-- dejaría un NDJSON con la cohorte de una enfermedad en un disco y a nadie que supiera que está ahí:
-- ni el sondeo lo menciona, ni hay cliente que pregunte por él, ni barrendero que lo reclame. Es
-- justamente el volcado olvidado que este proyecto lleva dos hitos evitando.
--
-- ⚠️ Y aquí NO hay filiación, ni por descuido ni por comodidad. Lo que se guarda es a qué cohorte
-- pertenece la exportación —un `Group/…`—, quién la pidió —el `sub` del testigo, que es un cliente y
-- no una persona—, y qué tipos de recurso salieron. Ni un id de paciente. Los pacientes están dentro
-- de los ficheros, que es lo que caduca y se borra.
--
-- Esquema propio, como `outbox` y `edo`: esto no es un agregado clínico y no vive en `dominio`. Lo que
-- modela es una CESIÓN de datos, con su rastro y su caducidad.

CREATE SCHEMA IF NOT EXISTS exportacion;

CREATE TABLE exportacion.trabajo (
    id            uuid        PRIMARY KEY,

    -- `Group/cohorte-legionelosis`. La referencia entera y no solo el id: dice de qué recurso se
    -- habla sin que haya que saberlo de memoria dentro de tres años.
    cohorte       text        NOT NULL,

    -- El `sub` del testigo que la pidió. Es un identificador de CLIENTE (`almacen-analitico`), no de
    -- persona. Se guarda porque «quién se llevó qué población y cuándo» es lo que hay que poder
    -- contestar de una cesión, y porque es lo que permite que solo quien la pidió pueda descargarla.
    -- NULL cuando la seguridad está apagada, que es solo en desarrollo.
    solicitante   text,

    -- El `transactionTime` del manifiesto: el corte temporal de la exportación. Se fija al ABRIR, no
    -- al terminar. Es el valor con el que el cliente pedirá su siguiente carga incremental, y ponerlo
    -- al final dejaría fuera todo lo escrito mientras la exportación corría.
    corte         timestamptz NOT NULL,

    -- EN_CURSO | TERMINADA | FALLIDA | CERRADA. Texto y no `enum`, como en el resto del esquema.
    estado        text        NOT NULL,

    -- Cuándo dejan de servirse los ficheros. Se pone al TERMINAR: un trabajo que tarda diez minutos no
    -- puede comerse el plazo de descarga del cliente. NULL mientras trabaja y una vez cerrada.
    caduca_en     timestamptz,

    -- Motivo técnico del fallo. Nunca clínico: una exportación no falla por lo que pongan los datos.
    motivo_fallo  text,

    creado_en     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT estado_de_exportacion_conocido
        CHECK (estado IN ('EN_CURSO', 'TERMINADA', 'FALLIDA', 'CERRADA')),

    -- El invariante del agregado, dicho también aquí: solo lo terminado se descarga, y solo lo
    -- terminado tiene plazo. Sin esto, un `UPDATE` a mano podría dejar un trabajo «cerrado» que
    -- todavía se sirve — es decir, un volcado accesible que el sistema cree haber retirado.
    CONSTRAINT solo_lo_terminado_caduca
        CHECK ((estado = 'TERMINADA' AND caduca_en IS NOT NULL) OR (estado <> 'TERMINADA' AND caduca_en IS NULL))
);

CREATE TABLE exportacion.fichero (
    -- EL BILLETE. Es lo único que viaja en la URL de descarga, y por eso es opaco: una URL acaba en el
    -- log del proxy, en el historial y en la analítica (adr-0016). No dice ni la cohorte, ni el tipo,
    -- ni desde luego el paciente.
    billete       text        PRIMARY KEY,

    trabajo_id    uuid        NOT NULL REFERENCES exportacion.trabajo (id) ON DELETE CASCADE,

    -- Qué hay dentro, que es lo que el manifiesto tiene que declarar por cada fichero.
    tipo_recurso  text        NOT NULL,

    -- Cómo se llama en el disco. Derivado del tipo, nunca de la cohorte ni del paciente.
    nombre        text        NOT NULL,
    recursos      bigint      NOT NULL,

    CONSTRAINT un_fichero_por_tipo_y_trabajo UNIQUE (trabajo_id, nombre)
);

-- El barrendero pregunta siempre lo mismo: qué se ha pasado de plazo. Índice PARCIAL, porque lo
-- cerrado y lo fallido no se vuelven a mirar por aquí.
CREATE INDEX trabajo_de_exportacion_por_caducar
    ON exportacion.trabajo (caduca_en)
    WHERE estado = 'TERMINADA';

COMMENT ON TABLE  exportacion.trabajo             IS 'Exportaciones masivas de cohortes: quién se llevó qué población, cuándo, y hasta cuándo se pudo descargar.';
COMMENT ON COLUMN exportacion.trabajo.corte       IS 'transactionTime del manifiesto. Se fija al abrir, no al terminar.';
COMMENT ON COLUMN exportacion.trabajo.caduca_en   IS 'Cuándo dejan de servirse los ficheros. El barrendero los borra del disco a partir de aquí.';
COMMENT ON COLUMN exportacion.fichero.billete     IS 'Identificador opaco de descarga. Nada de PHI en la URL.';
