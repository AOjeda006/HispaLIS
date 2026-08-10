-- El valor de una prueba cualitativa, como código (ítem 47).
--
-- Hasta ahora un `valueCodeableConcept` que llegaba por la API se guardaba en `valor_textual`,
-- prefiriendo el `text` al código. Para leerlo en un informe daba igual; para la regla de declaración
-- obligatoria no: esa regla compara CÓDIGOS, y con «Positivo» guardado como cadena nunca habría visto
-- un positivo. Es el mismo error que informar una cifra sin unidad, en cualitativo.
--
-- Es una columna nueva y no un uso distinto de `valor_textual` porque son dos cosas: una se compara y
-- la otra se lee. Un comentario del patólogo sobre una extensión no es un código, y meterlos en la
-- misma columna obligaría a adivinar cuál es cuál al sacarlos.

ALTER TABLE dominio.resultado
    ADD COLUMN valor_codificado text;

-- `algun_valor` (V2) decía «cifra o texto», y ahora hay una tercera forma que no contemplaba: un
-- cualitativo codificado la incumpliría sin tener nada de malo. Se sustituye en vez de aflojarse, y de
-- paso se aprieta — pasa de «al menos una» a «exactamente una».
--
-- Un resultado tiene UNA forma de valor. Las tres columnas conviven en la tabla porque un laboratorio
-- emite las tres clases, no porque una fila pueda tener varias: con dos rellenas, quien lee decide por
-- orden de comprobación, y dos lectores con distinto orden publican cosas distintas del mismo dato.
--
-- `unidad_ucum` no cuenta: no es una forma de valor, es parte de la cuantitativa, y que vaya con su
-- cifra ya lo garantiza `valor_con_unidad`.
ALTER TABLE dominio.resultado
    DROP CONSTRAINT algun_valor;

ALTER TABLE dominio.resultado
    ADD CONSTRAINT resultado_una_sola_forma_de_valor
        CHECK (num_nonnulls(valor, valor_textual, valor_codificado) = 1);

COMMENT ON COLUMN dominio.resultado.valor_codificado IS 'Valor de una prueba cualitativa (`POS`, `NEG`, `IND`). Es el que mira la regla EDO.';
