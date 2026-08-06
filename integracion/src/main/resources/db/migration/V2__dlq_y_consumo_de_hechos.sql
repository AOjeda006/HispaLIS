-- La DLQ y el consumo de hechos: lo que se pierde al no usar Mirth (D11) y hay que construir.
--
-- La DLQ NO es una tabla nueva. Es una consulta sobre `integracion.mensaje`: los que están en estado
-- RECHAZADO. Meterlos en otra tabla obligaría a moverlos de un lado a otro para reprocesarlos, y un
-- movimiento entre tablas es una transacción más que puede quedarse a medias — justo la avería que
-- esto existe para evitar. El original ya está guardado donde tiene que estar.

-- Cuántas veces se ha intentado, y cuándo fue la última. Sin esto no hay forma de distinguir un
-- mensaje que falló una vez de uno que lleva cuarenta reintentos contra un laboratorio caído, que es
-- lo primero que se pregunta cuando la bandeja crece.
ALTER TABLE integracion.mensaje
    ADD COLUMN intentos          integer     NOT NULL DEFAULT 0,
    ADD COLUMN ultimo_intento_en timestamptz;

COMMENT ON COLUMN integracion.mensaje.intentos IS 'Veces que se ha intentado aplicar, contando la entrega original y cada reproceso.';

-- Los hechos del `outbox` del laboratorio que este motor ya ha consumido.
--
-- El desplazamiento del consumidor es SUYO y vive en SU esquema. La alternativa —sellar
-- `outbox.hecho.publicado_en` desde aquí— parece más simple y es la avería clásica: esa columna es
-- del relay que publicará en Kafka (ítem 30), y dos consumidores marcando la misma casilla hacen que
-- el primero que pase deje al otro sin su hecho. Un consumidor lleva su propia cuenta; es lo que hace
-- Kafka con los grupos y es lo que hay que imitar mientras Kafka no esté.
CREATE TABLE integracion.hecho_consumido (
    hecho_id     uuid        PRIMARY KEY,
    consumido_en timestamptz NOT NULL,
    -- Qué se hizo con él. Un hecho consumido con error se puede volver a intentar borrando la fila,
    -- y el detalle dice por qué falló.
    resultado    text        NOT NULL,
    detalle      text,

    CONSTRAINT hecho_resultado_conocido CHECK (resultado IN ('ENTREGADO', 'DESCARTADO', 'FALLIDO'))
);

CREATE INDEX hecho_consumido_por_fecha ON integracion.hecho_consumido (consumido_en DESC);

COMMENT ON TABLE  integracion.hecho_consumido IS 'Desplazamiento propio del motor sobre el outbox del laboratorio. Se sustituye por el offset de Kafka en el ítem 30.';
COMMENT ON COLUMN integracion.hecho_consumido.resultado IS 'ENTREGADO = el HIS acusó AA · DESCARTADO = el hecho no interesa a este motor · FALLIDO = se intentó y no se pudo.';
