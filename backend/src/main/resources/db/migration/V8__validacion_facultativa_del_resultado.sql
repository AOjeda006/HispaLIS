-- La firma facultativa del resultado: quién responde de la cifra y desde cuándo.
--
-- Lo que sale de un analizador es una medida, no un resultado publicable. Entre las dos hay una
-- persona que la contrasta con la clínica y con los controles del día. Sin este paso el laboratorio
-- publicaba lo que dijera la máquina —avería del reactivo incluida— y lo firmaba con su sello.
--
-- De aquí cuelgan dos cosas que todavía no existen: el `ORU^R01` saliente hacia el HIS («cuando el
-- informe se valida») y el notificador EDO del hito 3 («cuando un resultado VALIDADO cae en el
-- catálogo de declaración obligatoria»). Sin estas dos columnas las dos tendrían que inventarse un
-- estado propio.
--
-- NO hay columna `estado`. El estado se deriva de si hay firma o no: una fila marcada como validada
-- sin nadie que la firme es justamente lo que este paso existe para impedir, y una combinación
-- imposible es mejor que ni siquiera se pueda escribir.

ALTER TABLE dominio.resultado
    ADD COLUMN validado_por text,
    ADD COLUMN validado_en  timestamptz;

-- Las dos van juntas o no va ninguna: describen un solo acto. Un autor sin fecha no dice si la
-- revisión es anterior o posterior al control de calidad, y una fecha sin autor no se puede reclamar.
ALTER TABLE dominio.resultado
    ADD CONSTRAINT resultado_validacion_completa CHECK (
        (validado_por IS NULL     AND validado_en IS NULL)
     OR (validado_por IS NOT NULL AND validado_en IS NOT NULL)
    );

COMMENT ON COLUMN dominio.resultado.validado_por IS 'Referencia al facultativo que firma (`Practitioner/…`). NULL = preliminar.';
COMMENT ON COLUMN dominio.resultado.validado_en  IS 'Cuándo se firmó. Se publica como Provenance.recorded.';
