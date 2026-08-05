-- Esquema del NÚCLEO DE DOMINIO, que es la fuente de verdad (§9 del diseño).
--
-- Vive aparte del de HAPI a propósito. HAPI crea sus tablas (`hfj_*`) en el esquema por defecto y
-- las gobierna él con sus propias migraciones; estas son nuestras y las gobierna Flyway. Comparten
-- base de datos —y por tanto transacción—, que es justo lo que permite escribir el dominio y su
-- proyección FHIR de una sola vez.

CREATE SCHEMA IF NOT EXISTS dominio;

CREATE TABLE dominio.paciente (
    id                  uuid        PRIMARY KEY,

    -- El NHC es único en el laboratorio y esa unicidad es un invariante de negocio, no una
    -- optimización: dos pacientes con el mismo número harían ambiguo a quién pertenece un
    -- resultado. Se declara en la base de datos porque una comprobación en Java no sobrevive a dos
    -- altas concurrentes.
    nhc                 char(8)     NOT NULL UNIQUE,

    -- Nombre familiar COMPLETO y sin trocear: «Muñoz de la Torre» es un solo valor. La
    -- descomposición, cuando se conoce, va en las dos columnas siguientes; nunca se deduce
    -- partiendo por el espacio.
    apellidos           text        NOT NULL,
    nombre_de_pila      text        NOT NULL,
    apellido_padre      text,
    apellido_madre      text,

    -- Identificadores que el laboratorio NO emite (D16): cadena opaca, opcional y sin validar. Su
    -- estructura la fija un Real Decreto y ya ha cambiado tres veces; validarla aquí convertiría un
    -- cambio normativo en un despliegue urgente.
    dni_nie             text,
    cip_autonomico      text,
    cip_sns             text,
    nass                text,

    sexo                text        NOT NULL,
    fecha_nacimiento    date,
    activo              boolean     NOT NULL DEFAULT true,

    creado_en           timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE  dominio.paciente             IS 'Paciente del laboratorio. Fuente de verdad; la proyección FHIR se deriva de aquí.';
COMMENT ON COLUMN dominio.paciente.nhc         IS 'Número de historia clínica, ocho dígitos. Único identificador que emite el laboratorio.';
COMMENT ON COLUMN dominio.paciente.apellidos   IS 'Nombre familiar completo, sin partir.';
COMMENT ON COLUMN dominio.paciente.cip_autonomico IS 'CIP de la comunidad autónoma; en Andalucía, el NUHSA. A menudo ausente en un laboratorio privado.';

-- Se busca por estos identificadores desde el borde FHIR, y son opcionales: el índice parcial deja
-- fuera las filas sin valor, que son muchas.
CREATE INDEX idx_paciente_dni_nie        ON dominio.paciente (dni_nie)        WHERE dni_nie IS NOT NULL;
CREATE INDEX idx_paciente_cip_autonomico ON dominio.paciente (cip_autonomico) WHERE cip_autonomico IS NOT NULL;
CREATE INDEX idx_paciente_cip_sns        ON dominio.paciente (cip_sns)        WHERE cip_sns IS NOT NULL;
