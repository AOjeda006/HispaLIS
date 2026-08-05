-- Cuándo se hizo la determinación y quién la hizo.
--
-- Los dos son `Must Support` en el perfil `ResultadoLab` y hasta ahora el laboratorio los perdía: el
-- recurso podía traerlos y la proyección los tiraba. Un resultado sin fecha de medición ni autor es
-- clínicamente incompleto —no se sabe si es de esta mañana o del mes pasado, ni a quién se reclama—,
-- y `Must Support` significa exactamente que el servidor tiene que ser capaz de recibirlos,
-- guardarlos y devolverlos.
--
-- Ambas columnas son NULL-ables porque el perfil los declara opcionales (`0..1` y `0..*`). Exigirlos
-- aquí sería que el servidor rechazara recursos que su propia guía admite. Lo que NO se hace es
-- rellenarlos solos: la hora de registro no es la hora de la medición, y confundirlas coloca un
-- resultado de ayer entre los de hoy.

ALTER TABLE dominio.resultado
    ADD COLUMN medido_en     timestamptz,
    ADD COLUMN realizado_por text;

COMMENT ON COLUMN dominio.resultado.medido_en     IS 'Cuándo se hizo la determinación (`Observation.effective[x]`), no cuándo se registró.';
COMMENT ON COLUMN dominio.resultado.realizado_por IS 'Referencia a quien la hizo: `Organization/…` o `Practitioner/…`.';
