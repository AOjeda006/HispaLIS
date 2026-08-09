-- La doble validación del resultado crítico (§10, ítem 46).
--
-- Un resultado cuyo valor cae en el catálogo de críticos no se publica con una sola firma: exige una
-- segunda de un facultativo DISTINTO. Eso rompe la forma en la que la V8 guardaba la validación —dos
-- columnas en la propia fila del resultado—, porque ahora las firmas son varias y hay que poder
-- reclamarlas por separado: cada una es un acto de una persona concreta y tiene su `Provenance`.
--
-- Por qué una tabla y no un par de columnas más (`validado_por_2`, `validado_en_2`):
--
--   1. La restricción de UNICIDAD por (resultado, facultativo) es la regla del ítem, escrita en el
--      único sitio donde no se puede rodear. Con columnas paralelas habría que compararlas a mano en
--      cada camino de escritura, y la base de datos no podría ayudar.
--   2. `orden` deja constancia de QUIÉN firmó primero, que es lo que distingue la revisión inicial de
--      la contra-revisión. Con dos columnas el orden sería el de las columnas, no el de los hechos.
--
-- NO hay columna `estado`, igual que en la V8 y por lo mismo. Lo que sí hay es `firmas_exigidas`, que
-- no es un estado: es cuántas firmas pidió el catálogo cuando se puso la primera. Se graba para que
-- una caída de la terminología entre las dos firmas no bloquee la segunda, y para que un cambio del
-- catálogo a mitad de camino no pueda rebajar a una firma lo que empezó exigiendo dos.

CREATE TABLE dominio.validacion_de_resultado (
    resultado_id uuid        NOT NULL REFERENCES dominio.resultado (id) ON DELETE CASCADE,
    orden        smallint    NOT NULL,
    facultativo  text        NOT NULL,
    realizada_en timestamptz NOT NULL,

    PRIMARY KEY (resultado_id, orden),

    -- El invariante del ítem 46, en la base de datos: la misma persona no firma dos veces el mismo
    -- resultado. Mirando dos veces no se revisa nada — quien leyó mal la cifra la vuelve a leer mal.
    CONSTRAINT validacion_de_otro_facultativo UNIQUE (resultado_id, facultativo),

    CONSTRAINT validacion_en_orden CHECK (orden BETWEEN 1 AND 2)
);

COMMENT ON TABLE  dominio.validacion_de_resultado           IS 'Las firmas facultativas de un resultado. Una la corriente; dos las de un valor crítico.';
COMMENT ON COLUMN dominio.validacion_de_resultado.orden     IS '1 = revisión inicial, 2 = contra-revisión del crítico. El orden de los hechos, no el de las columnas.';
COMMENT ON COLUMN dominio.validacion_de_resultado.facultativo IS 'Referencia a quien firma (`Practitioner/…`). Se publica como Provenance.agent.who.';

ALTER TABLE dominio.resultado
    ADD COLUMN firmas_exigidas smallint;

-- Ni cero ni tres: cero sería un resultado que se valida solo y tres no lo pide ninguna regla de este
-- laboratorio. Que el rango esté aquí y no solo en el dominio es la red de la escritura directa.
ALTER TABLE dominio.resultado
    ADD CONSTRAINT resultado_firmas_exigidas_conocidas CHECK (firmas_exigidas IS NULL OR firmas_exigidas IN (1, 2));

-- Lo ya firmado se traslada tal cual: existía antes de que hubiera críticos, así que pedía una firma
-- y la tiene. Reinterpretarlo como «pendiente de la segunda» inventaría un trabajo que nadie encargó.
INSERT INTO dominio.validacion_de_resultado (resultado_id, orden, facultativo, realizada_en)
SELECT id, 1, validado_por, validado_en
  FROM dominio.resultado
 WHERE validado_por IS NOT NULL;

UPDATE dominio.resultado
   SET firmas_exigidas = 1
 WHERE validado_por IS NOT NULL;

ALTER TABLE dominio.resultado
    DROP CONSTRAINT resultado_validacion_completa,
    DROP COLUMN validado_por,
    DROP COLUMN validado_en;

COMMENT ON COLUMN dominio.resultado.firmas_exigidas IS 'Cuántas firmas pidió el catálogo al ponerse la primera. NULL = todavía no ha firmado nadie.';
