-- De dónde viene una determinación que nadie pidió por volante (`Observation.triggeredBy`, ítem 45).
--
-- Son dos cosas distintas y por eso son dos tablas:
--
--   * `peticion.disparada_por`  — el laboratorio AÑADE una línea al volante porque otra prueba salió
--     alterada. Es la prueba refleja, y la decide el catálogo (propiedad `prueba-refleja`), no el
--     código. Hereda volante, paciente y solicitante de la línea que la disparó: R5 dice en la propia
--     definición del código `reflex` que la petición original es «the one that provided the
--     authorization».
--
--   * `resultado.disparo_*`     — el resultado que sale de ahí, o el que repite a otro, enlaza con el
--     que lo provocó. Aquí caben los tres códigos, y los tres son necesarios: `repeat` (la muestra
--     estaba mal) y `re-run` (el analizador estaba mal) los declara quien repite, porque la hemólisis
--     del tubo y el control de calidad del turno solo los ve él.
--
-- El MOTIVO es obligatorio siempre que haya disparo, y no es burocracia: sin él, la historia enseña
-- dos cifras de la misma prueba que se contradicen —un potasio de 6,9 y otro de 4,3 el mismo día— y
-- un enlace entre ellas que no dice cuál vale. La frase la redacta quien redacta la regla.

ALTER TABLE dominio.peticion
    ADD COLUMN disparada_por      uuid REFERENCES dominio.resultado (id),
    ADD COLUMN motivo_del_disparo text;

-- Las dos van juntas o no va ninguna: una línea añadida por el laboratorio sin poder decir por qué
-- es exactamente lo que esto existe para impedir.
ALTER TABLE dominio.peticion
    ADD CONSTRAINT peticion_disparo_completo CHECK (
        (disparada_por IS NULL     AND motivo_del_disparo IS NULL)
     OR (disparada_por IS NOT NULL AND motivo_del_disparo IS NOT NULL)
    );

-- Al informar un resultado hay que saber si su prueba refleja ya está pedida en ese volante, o
-- reinformar la misma TSH añadiría una segunda T4 libre en cada vuelta.
CREATE INDEX peticion_por_volante_y_prueba ON dominio.peticion (paciente_id, numero_de_peticion, codigo_de_prueba);

ALTER TABLE dominio.resultado
    ADD COLUMN disparo_origen uuid REFERENCES dominio.resultado (id),
    ADD COLUMN disparo_tipo   text,
    ADD COLUMN disparo_motivo text;

-- Texto y no `enum` de PostgreSQL, como en `outbox.hecho.tipo`: el conjunto de valores lo cierra el
-- dominio (`TipoDeDisparo`), que es donde vive la regla, y añadir uno no debe exigir una migración.
-- Lo que sí se comprueba aquí es que los tres campos describen un solo hecho y no medio.
ALTER TABLE dominio.resultado
    ADD CONSTRAINT resultado_disparo_completo CHECK (
        (disparo_origen IS NULL     AND disparo_tipo IS NULL     AND disparo_motivo IS NULL)
     OR (disparo_origen IS NOT NULL AND disparo_tipo IS NOT NULL AND disparo_motivo IS NOT NULL)
    );

-- Y que un resultado no se dispare a sí mismo, que es el ciclo de longitud uno y el único que una
-- restricción de fila puede detectar. Los más largos no los impide la base de datos.
ALTER TABLE dominio.resultado
    ADD CONSTRAINT resultado_disparo_no_circular CHECK (disparo_origen IS DISTINCT FROM id);

COMMENT ON COLUMN dominio.peticion.disparada_por      IS 'Resultado alterado que provocó que el laboratorio añadiera esta línea. NULL = la pidió el peticionario.';
COMMENT ON COLUMN dominio.peticion.motivo_del_disparo IS 'La frase del catálogo (`motivo-de-la-refleja`) que explica por qué existe la línea.';
COMMENT ON COLUMN dominio.resultado.disparo_origen    IS 'El resultado que provocó este. Se publica en Observation.triggeredBy.observation.';
COMMENT ON COLUMN dominio.resultado.disparo_tipo      IS 'reflex | repeat | re-run, tal y como viaja en FHIR.';
COMMENT ON COLUMN dominio.resultado.disparo_motivo    IS 'Por qué existe esta determinación, en español. Va a triggeredBy.reason y de ahí a la pantalla.';
