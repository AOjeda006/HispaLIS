-- Los dos agregados que faltaban para cerrar el circuito: petición → … → informe.

CREATE TABLE dominio.peticion (
    id                  uuid        PRIMARY KEY,

    -- Lo que el laboratorio y el peticionario llaman «la petición» son varias LÍNEAS que comparten
    -- este número. No es único, y no debe serlo: un hemograma, una glucosa y una TSH del mismo
    -- volante son tres filas con el mismo `numero_de_peticion`, y cada una avanza a su ritmo.
    numero_de_peticion  text        NOT NULL,

    paciente_id         uuid        NOT NULL REFERENCES dominio.paciente (id),
    codigo_de_prueba    text        NOT NULL,

    -- Referencia opaca al peticionario. El facultativo y la organización son datos maestros del
    -- laboratorio, no agregados con invariantes propios (§10 del diseño).
    solicitante         text        NOT NULL,

    solicitada_en       timestamptz NOT NULL,
    creado_en           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_peticion_numero   ON dominio.peticion (numero_de_peticion);
CREATE INDEX idx_peticion_paciente ON dominio.peticion (paciente_id);

-- Un resultado puede venir de una línea de petición, o no: una repetición de control o una
-- determinación añadida en el laboratorio existen aunque nadie las pidiera por volante.
ALTER TABLE dominio.resultado
    ADD COLUMN peticion_id uuid REFERENCES dominio.peticion (id);

CREATE INDEX idx_resultado_peticion ON dominio.resultado (peticion_id) WHERE peticion_id IS NOT NULL;

CREATE TABLE dominio.informe (
    id                  uuid        PRIMARY KEY,
    paciente_id         uuid        NOT NULL REFERENCES dominio.paciente (id),

    -- Quién firma el informe: de eso depende a quién se reclama.
    emisor              text        NOT NULL,

    emitido_en          timestamptz NOT NULL,
    creado_en           timestamptz NOT NULL DEFAULT now()
);

-- Los resultados que componen el informe. Tabla aparte y no un array porque la relación es real y
-- se consulta en los dos sentidos: qué lleva un informe, y en qué informe salió un resultado.
CREATE TABLE dominio.informe_resultado (
    informe_id          uuid        NOT NULL REFERENCES dominio.informe (id),
    resultado_id        uuid        NOT NULL REFERENCES dominio.resultado (id),
    PRIMARY KEY (informe_id, resultado_id)
);

CREATE INDEX idx_informe_paciente            ON dominio.informe (paciente_id);
CREATE INDEX idx_informe_resultado_resultado ON dominio.informe_resultado (resultado_id);

COMMENT ON TABLE  dominio.peticion                    IS 'Línea de petición analítica. Varias líneas comparten numero_de_peticion.';
COMMENT ON COLUMN dominio.peticion.numero_de_peticion IS 'Agrupa las líneas del mismo volante. NO es único.';
COMMENT ON TABLE  dominio.informe                     IS 'Informe emitido. Nunca vacío: un informe sin resultados no informa de nada.';
