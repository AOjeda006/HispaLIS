-- Las declaraciones de enfermedad obligatoria y el desplazamiento del notificador (ítem 48).
--
-- Dos tablas y dos esquemas distintos, y la separación no es cosmética:
--
--   `dominio.notificacion_edo`  es la OBLIGACIÓN. Un agregado más del laboratorio, con su ciclo de
--                               vida, su plazo y su acuse. Se proyecta a `Task` como el resultado se
--                               proyecta a `Observation`.
--   `edo.hecho_consumido`       es el DESPLAZAMIENTO del notificador sobre `outbox.hecho`.
--
-- ⚠️ Lo segundo NO se puede resolver marcando `outbox.hecho.publicado_en`, que es la casilla del
-- relay a Kafka: dos consumidores marcando la misma dejarían al otro sin su hecho. Cada consumidor
-- lleva su propio desplazamiento en su propio esquema, que es lo que hace cualquier grupo de
-- consumidores — y lo que ya hace el motor de integración con `NotificadorAlHis`.
--
-- ⚠️ Y aquí NO hay filiación. Ni nombre, ni NHC, ni NUHSA: solo el identificador interno del caso y
-- códigos. Es el invariante 6 escrito en el esquema, en el sitio donde más barato sale romperlo,
-- porque el destinatario legítimamente «necesita saber». Lo que necesita saber lo resuelve contra la
-- API, donde se aplica el consentimiento.

CREATE TABLE dominio.notificacion_edo (
    id                     uuid        PRIMARY KEY,

    -- El resultado validado que obliga a declarar. UNIQUE: la entrega del outbox es «al menos una
    -- vez», así que el mismo hecho puede llegar dos veces, y dos declaraciones del mismo caso
    -- inflarían el recuento de Salud Pública. En vigilancia epidemiológica eso no es inocuo: un
    -- número de más dispara una investigación que no toca.
    resultado_id           uuid        NOT NULL UNIQUE REFERENCES dominio.resultado (id),
    paciente_id            uuid        NOT NULL REFERENCES dominio.paciente (id),

    -- Quién declara: el centro que emitió el resultado, como referencia. Se guarda aquí y no se
    -- deduce del resultado en cada paso, porque quién declaró es de lo que hay que responder años
    -- después y el `performer` de un resultado se puede corregir.
    declarante             text,

    -- Códigos de `CodeSystem/enfermedades-edo`, no CIE-10: una EDO es una entrada de una lista
    -- administrativa, no el diagnóstico del paciente. El nombre se guarda junto al código porque es
    -- el que se imprimió el día que se declaró, y el catálogo puede cambiarlo mañana.
    codigo_enfermedad      text        NOT NULL,
    nombre_enfermedad      text        NOT NULL,

    -- URGENTE | ORDINARIA. Texto y no `enum`, como en el resto del esquema.
    modalidad              text        NOT NULL,

    -- Cuándo nació la obligación: el instante en que se validó el resultado, NO cuando al notificador
    -- le tocó el turno. Si el laboratorio arrastra cola, el plazo no se estira.
    abierta_en             timestamptz NOT NULL,

    -- El vencimiento se GUARDA calculado, no se deriva al leer. Si mañana el catálogo cambia el plazo
    -- de la legionelosis, las declaraciones ya abiertas conservan la ventana que tenían: el plazo que
    -- se incumplió es el que estaba vigente ese día. Misma decisión que `resultado.firmas_exigidas`.
    vencimiento            timestamptz NOT NULL,

    -- PENDIENTE | ENVIADA | ACUSADA | RECHAZADA. Ver `CodeSystem/estados-declaracion-edo`.
    estado                 text        NOT NULL,
    intentos               integer     NOT NULL DEFAULT 0,

    -- Motivo técnico o respuesta del destinatario. Nunca clínico: aquí no hay nada del paciente que
    -- contar que no esté ya en el código de la enfermedad.
    ultimo_error           text,

    -- EL ACUSE. El `system` dice de quién es el número, porque el laboratorio no lo emite y no puede
    -- fingirlo.
    acuse_sistema          text,
    acuse_numero           text,
    acuse_recibido_en      timestamptz,

    -- El invariante del ítem, escrito donde no se puede rodear: ACUSADA exige acuse, y solo ACUSADA
    -- lo tiene. Sin esto, un `UPDATE ... SET estado = 'ACUSADA'` a mano daría por declarado algo de lo
    -- que no hay recibo — que es exactamente lo que habría que enseñar si alguien pregunta.
    CONSTRAINT acusada_exige_acuse CHECK (
        (estado = 'ACUSADA' AND acuse_numero IS NOT NULL AND acuse_recibido_en IS NOT NULL)
        OR (estado <> 'ACUSADA' AND acuse_numero IS NULL)
    ),
    CONSTRAINT estado_conocido CHECK (estado IN ('PENDIENTE', 'ENVIADA', 'ACUSADA', 'RECHAZADA')),
    CONSTRAINT modalidad_conocida CHECK (modalidad IN ('URGENTE', 'ORDINARIA'))
);

-- El notificador pregunta siempre lo mismo: qué queda por declarar, en orden de vencimiento — lo que
-- antes se pasa de plazo, primero. Índice PARCIAL: lo cerrado no se vuelve a mirar por aquí.
CREATE INDEX notificacion_edo_abierta
    ON dominio.notificacion_edo (vencimiento)
    WHERE estado IN ('PENDIENTE', 'ENVIADA');

COMMENT ON TABLE  dominio.notificacion_edo                IS 'Declaraciones de EDO a Salud Pública: la obligación, su plazo y su acuse.';
COMMENT ON COLUMN dominio.notificacion_edo.vencimiento    IS 'Fecha límite legal, congelada al abrir. No se recalcula si el catálogo cambia.';
COMMENT ON COLUMN dominio.notificacion_edo.acuse_numero   IS 'Número de registro de Salud Pública. Sin él la declaración NO está hecha.';


-- El desplazamiento del notificador sobre el outbox, con la misma forma que el del motor.
CREATE SCHEMA IF NOT EXISTS edo;

CREATE TABLE edo.hecho_consumido (
    hecho_id      uuid        PRIMARY KEY REFERENCES outbox.hecho (id),

    -- ATENDIDO | DESCARTADO. Descartado es lo normal: por el outbox pasan todos los hechos del
    -- laboratorio y a este consumidor solo le interesa uno. Se anotan igual para que la consulta de
    -- pendientes no arrastre para siempre lo que este notificador no mira.
    consumo       text        NOT NULL,
    detalle       text,
    consumido_en  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT consumo_conocido CHECK (consumo IN ('ATENDIDO', 'DESCARTADO'))
);

COMMENT ON TABLE edo.hecho_consumido IS 'Qué hechos del outbox ha visto ya el notificador EDO. Su propio desplazamiento: la casilla publicado_en es del relay a Kafka.';
