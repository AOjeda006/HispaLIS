-- Los rangos de referencia que publica este laboratorio.
--
-- Van en la base de datos y NO en la guía de implementación, y la distinción importa: los códigos de
-- prueba son terminología —comunes a todo el que hable con este laboratorio, y por eso salen del
-- `CodeSystem` (D15)—, mientras que los rangos dependen del método y del analizador de CADA
-- laboratorio. Dos laboratorios que usan el mismo código `CREA` publican rangos distintos sin
-- contradecirse. Son configuración, no vocabulario compartido.
--
-- `sexo` NULL significa «vale para cualquiera». Solo la serie roja y la creatinina lo distinguen, y
-- no es un matiz: una hemoglobina de 13 g/dL es normal en una mujer y baja en un hombre, así que sin
-- el rango por sexo la mitad de las interpretaciones de la serie roja estarían mal.

CREATE TABLE dominio.rango_de_referencia (
    codigo_de_prueba text        NOT NULL,
    sexo             text,
    bajo             numeric     NOT NULL,
    alto             numeric     NOT NULL,
    unidad_ucum      text        NOT NULL,

    CONSTRAINT limites_ordenados CHECK (bajo <= alto),
    CONSTRAINT sexo_conocido     CHECK (sexo IS NULL OR sexo IN ('male', 'female'))
);

-- Un rango por prueba y sexo, y ni uno más: dos rangos solapados para el mismo paciente harían
-- ambiguo cuál se aplica.
--
-- Van DOS índices parciales en vez de uno con `NULLS NOT DISTINCT` porque esa cláusula es de
-- PostgreSQL 15 y aquí se corre también sobre la 14. Un índice único corriente no sirve: para él dos
-- filas con `sexo` NULL son distintas, así que el rango común se podría duplicar sin que nadie
-- protestara.
CREATE UNIQUE INDEX idx_rango_por_sexo
    ON dominio.rango_de_referencia (codigo_de_prueba, sexo) WHERE sexo IS NOT NULL;
CREATE UNIQUE INDEX idx_rango_comun
    ON dominio.rango_de_referencia (codigo_de_prueba) WHERE sexo IS NULL;

INSERT INTO dominio.rango_de_referencia (codigo_de_prueba, sexo, bajo, alto, unidad_ucum) VALUES
    ('GLU',   NULL,     70,    100,  'mg/dL'),
    ('CREA',  'male',   0.70,  1.30, 'mg/dL'),
    ('CREA',  'female', 0.60,  1.10, 'mg/dL'),
    ('UREA',  NULL,     17,    43,   'mg/dL'),
    ('NA',    NULL,     135,   145,  'mmol/L'),
    ('K',     NULL,     3.50,  5.10, 'mmol/L'),
    ('COLT',  NULL,     120,   200,  'mg/dL'),
    ('GOT',   NULL,     5,     34,   'U/L'),
    ('GPT',   NULL,     5,     55,   'U/L'),
    ('PCR',   NULL,     0,     5,    'mg/L'),
    ('HBA1C', NULL,     4.0,   5.7,  '%'),
    ('HB',    'male',   13.5,  17.5, 'g/dL'),
    ('HB',    'female', 12.0,  16.0, 'g/dL'),
    ('HTO',   'male',   40.0,  52.0, '%'),
    ('HTO',   'female', 36.0,  47.0, '%'),
    ('LEU',   NULL,     4.0,   11.0, '10*3/uL'),
    ('PLAQ',  NULL,     150,   400,  '10*3/uL'),
    ('TSH',   NULL,     0.40,  4.00, 'u[IU]/mL'),
    ('T4L',   NULL,     0.70,  1.90, 'ng/dL');

COMMENT ON TABLE  dominio.rango_de_referencia      IS 'Entre qué cifras es normal cada prueba. Configuración del laboratorio, no terminología.';
COMMENT ON COLUMN dominio.rango_de_referencia.sexo IS 'NULL = el rango vale para cualquier paciente.';
