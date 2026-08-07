package es.hispalis.backend.infraestructura.bus;

import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.entities.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deja los cuatro esquemas registrados y la compatibilidad fijada <strong>hacia atrás</strong>.
 *
 * <p>El registro es explícito y el serializador va con {@code auto.register.schemas=false}. Es lo
 * contrario del ajuste por defecto de Confluent, y a propósito: con el registro automático, cambiar
 * un {@code .avsc} y arrancar publica una versión nueva sin que nadie la mire, y la primera noticia
 * de que se rompió un consumidor llega cuando ese consumidor falla. Con el registro apagado, el
 * único que puede crear versiones es este código, y crearlas pasa por la puerta de compatibilidad.
 *
 * <p><strong>Compatibilidad hacia atrás</strong> (§11) quiere decir que un consumidor con el esquema
 * nuevo puede leer lo que se escribió con el viejo. En la práctica: se pueden añadir campos con
 * valor por defecto y quitar campos; no se puede añadir un campo obligatorio, ni renombrar, ni
 * cambiar el tipo de uno que ya existe. Lo que no quepa ahí no es una versión: es un tópico
 * {@code .v2}.
 *
 * <p>No corre al arrancar. Si el registro está caído, la aplicación tiene que levantar igual —la API
 * FHIR no depende del bus—, así que esto se intenta desde el relay y se reintenta en cada vuelta
 * hasta que sale.
 */
class EsquemasDelBus {

    private static final Logger LOG = LoggerFactory.getLogger(EsquemasDelBus.class);

    private final SchemaRegistryClient registro;
    private boolean registrados;

    EsquemasDelBus(SchemaRegistryClient registro) {
        this.registro = registro;
    }

    /**
     * Registra lo que falte. Idempotente: registrar el mismo esquema otra vez devuelve el mismo
     * identificador y no crea versión.
     *
     * @throws BusNoDisponible si el registro no responde
     */
    void asegurarRegistrados() {
        if (registrados) {
            return;
        }
        try {
            for (Topico topico : Topico.values()) {
                // El nivel se fija POR SUJETO y no solo en el servidor: el ajuste global del
                // registro se puede cambiar desde fuera, y entonces estos cuatro tópicos dejarían de
                // estar protegidos sin que este repositorio se enterase.
                registro.updateConfig(topico.sujetoEnElRegistro(), new Config(CompatibilityLevel.BACKWARD.name));
                int id = registro.register(topico.sujetoEnElRegistro(), new AvroSchema(topico.esquema()));
                LOG.debug("Esquema de {} registrado con id {}", topico.nombre(), id);
            }
            registrados = true;
            LOG.info("Los cuatro esquemas del bus están registrados, con compatibilidad hacia atrás.");
        } catch (Exception e) {
            throw new BusNoDisponible("No se pudieron registrar los esquemas del bus", e);
        }
    }
}
