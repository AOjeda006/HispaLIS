-- La línea de petición gana estado, para poder anularse.
--
-- El invariante del informe (§10) bloquea la emisión mientras quede una línea sin resolver, y una
-- muestra rechazada no produce resultado nunca. Sin estado, ese volante quedaba bloqueado a la
-- espera de una extracción que puede no llegar. Un laboratorio de verdad anula la línea, que en FHIR
-- se publica como `ServiceRequest.status = revoked`.
--
-- Solo hay dos estados. «En curso», «recibida» o «en el analizador» son estados del ESPÉCIMEN y del
-- RESULTADO, que son otros agregados: repetirlos aquí crearía dos copias del mismo hecho que pueden
-- contradecirse.

ALTER TABLE dominio.peticion
    ADD COLUMN estado              text        NOT NULL DEFAULT 'ACTIVA',
    ADD COLUMN motivo_de_anulacion text,
    ADD COLUMN anulada_en          timestamptz;

-- El DEFAULT existía solo para poblar las filas que ya había. Dejarlo puesto convertiría un olvido
-- del código en una línea activa creada en silencio, así que se retira en cuanto ha hecho su trabajo.
ALTER TABLE dominio.peticion
    ALTER COLUMN estado DROP DEFAULT;

-- Anular sin motivo deja al peticionario viendo una prueba que pidió y que no se le entrega, sin
-- saber si reclamarla o repetir la extracción. Es la misma regla que el agregado comprueba al
-- anular; aquí abajo se declara para que no exista forma de escribirla mal, venga de donde venga.
ALTER TABLE dominio.peticion
    ADD CONSTRAINT peticion_anulada_documenta_el_motivo CHECK (
        (estado = 'ACTIVA'  AND motivo_de_anulacion IS NULL     AND anulada_en IS NULL)
     OR (estado = 'ANULADA' AND motivo_de_anulacion IS NOT NULL AND anulada_en IS NOT NULL)
    );

COMMENT ON COLUMN dominio.peticion.estado              IS 'ACTIVA | ANULADA. Se proyecta como ServiceRequest.status (active | revoked).';
COMMENT ON COLUMN dominio.peticion.motivo_de_anulacion IS 'Por qué se retiró la línea. Obligatorio si está anulada.';
