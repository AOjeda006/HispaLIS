-- El relay ya existe (§11, ítem 30), y con él aparece un estado que la V9 no contemplaba: un hecho
-- que NUNCA va a publicarse.
--
-- Los tópicos del diseño son cuatro y son del laboratorio —peticiones, especímenes, resultados e
-- informes—. Los hechos de filiación (`PACIENTE_REGISTRADO`, `PACIENTE_ACTUALIZADO`) no tienen
-- tópico: la demografía es asunto del HIS, que es quien la manda, no algo que el laboratorio anuncie.
-- Con una sola columna `publicado_en` esas filas solo podían quedarse pendientes para siempre
-- —engordando el índice parcial y haciendo que el relay las mirara en cada vuelta— o marcarse como
-- publicadas, que es mentira y se descubre el día que alguien audite qué salió al bus.
--
-- Así que se distinguen los dos finales y se guarda el tópico: la tabla pasa a poder responder «qué
-- se publicó, dónde y qué no salió», que es justo lo que se le pregunta a un outbox cuando algo va
-- mal.

ALTER TABLE outbox.hecho ADD COLUMN descartado_en timestamptz;
ALTER TABLE outbox.hecho ADD COLUMN topico        text;

-- El índice parcial de la V9 solo excluía lo publicado. Un hecho descartado tampoco es trabajo
-- pendiente, y dejarlo dentro convertiría el índice en una lista creciente de filas que el relay
-- descarta una y otra vez.
DROP INDEX outbox.hecho_pendiente;
CREATE INDEX hecho_pendiente ON outbox.hecho (creado_en)
    WHERE publicado_en IS NULL AND descartado_en IS NULL;

COMMENT ON COLUMN outbox.hecho.publicado_en IS 'Cuándo llegó al bus. NULL = todavía no.';
COMMENT ON COLUMN outbox.hecho.descartado_en IS 'Cuándo el relay decidió que su tipo no tiene tópico. NULL = no descartado. Nunca se borra la fila: el outbox es la prueba de qué apuntó el laboratorio.';
COMMENT ON COLUMN outbox.hecho.topico IS 'Tópico al que salió. NULL mientras no se publique, y NULL para siempre si se descartó.';
