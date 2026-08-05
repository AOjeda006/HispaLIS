-- Muestra y resultado: los dos agregados que sostienen el invariante de C6 —una muestra rechazada
-- no produce resultados—.
--
-- El `id` de cada agregado es TAMBIÉN el id lógico del recurso FHIR que lo proyecta. No hay tabla de
-- correspondencias ni columna `fhir_id`: una referencia `Specimen/<uuid>` que llega por la API
-- resuelve directamente al agregado. Eso es lo que permite comprobar el invariante sin leer la
-- proyección, y lo que hará trivial el reconciliador del hito 2.

CREATE TABLE dominio.especimen (
    id                  uuid        PRIMARY KEY,

    -- El código con el que la muestra circula físicamente: va en la etiqueta, lo lee el analizador y
    -- es lo que une el tubo con el resultado. Único, porque dos muestras con el mismo número harían
    -- ambiguo a cuál pertenece un resultado.
    numero_de_acceso    text        NOT NULL UNIQUE,

    paciente_id         uuid        NOT NULL REFERENCES dominio.paciente (id),
    tipo                text        NOT NULL,

    estado              text        NOT NULL,

    -- Obligatorio si el estado es RECHAZADA, y la restricción lo exige aquí además de en el
    -- agregado: rechazar sin decir por qué obliga al peticionario a llamar por teléfono.
    motivo_de_rechazo   text,

    creado_en           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT rechazo_motivado CHECK (estado <> 'RECHAZADA' OR motivo_de_rechazo IS NOT NULL)
);

CREATE TABLE dominio.resultado (
    id                  uuid        PRIMARY KEY,
    especimen_id        uuid        NOT NULL REFERENCES dominio.especimen (id),
    paciente_id         uuid        NOT NULL REFERENCES dominio.paciente (id),

    -- Código del catálogo local del laboratorio (GLU, CREA, TSH…), no LOINC: la traducción a LOINC
    -- la hace el `ConceptMap` de la guía, que es su sitio.
    codigo_de_prueba    text        NOT NULL,

    -- Cuantitativo: cifra y unidad SIEMPRE juntas. Una cifra sin unidad no significa nada, así que
    -- la restricción impide guardar una sin la otra.
    valor               numeric,
    unidad_ucum         text,

    -- Cualitativo o textual: lo que no se deja medir.
    valor_textual       text,

    creado_en           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT valor_con_unidad CHECK ((valor IS NULL) = (unidad_ucum IS NULL)),
    CONSTRAINT algun_valor      CHECK (valor IS NOT NULL OR valor_textual IS NOT NULL)
);

CREATE INDEX idx_resultado_especimen ON dominio.resultado (especimen_id);
CREATE INDEX idx_resultado_paciente  ON dominio.resultado (paciente_id);

COMMENT ON TABLE  dominio.especimen                   IS 'Muestra recibida. Una RECHAZADA no puede producir resultados (C6).';
COMMENT ON COLUMN dominio.especimen.numero_de_acceso  IS 'Código de la etiqueta; une el tubo con el resultado.';
COMMENT ON TABLE  dominio.resultado                   IS 'Determinación analítica sobre una muestra.';
COMMENT ON COLUMN dominio.resultado.codigo_de_prueba  IS 'Código del catálogo local, no LOINC.';
