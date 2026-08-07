package es.hispalis.backend.infraestructura.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.SchemaProvider;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientFactory;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * El bus de salida: productor, registro de esquemas y relay.
 *
 * <p>Todo cuelga de {@code hispalis.bus.habilitado}. Con el bus apagado no se crea ni el productor,
 * y eso es lo que permite que los más de cien tests que no van de esto no arrastren un cliente de
 * Kafka reintentando contra un broker que no existe.
 *
 * <p>El productor se arma aquí a mano en vez de dejárselo a la autoconfiguración porque hay tres
 * ajustes que no son opcionales y que un valor por defecto cambiaría en silencio: la idempotencia
 * del productor, el registro automático de esquemas <strong>apagado</strong> y un {@code max.block.ms}
 * corto. Los tres están comentados donde se ponen.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PropiedadesDelBus.class)
@ConditionalOnProperty(prefix = "hispalis.bus", name = "habilitado", havingValue = "true", matchIfMissing = true)
class ConfiguracionDelBus {

    @Bean
    ProducerFactory<String, SpecificRecord> productorDeHechos(
            KafkaProperties propiedadesDeKafka, PropiedadesDelBus bus) {
        Map<String, Object> ajustes = new HashMap<>(propiedadesDeKafka.buildProducerProperties(null));

        ajustes.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        ajustes.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        ajustes.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, bus.registroDeEsquemas());

        // Ver `EsquemasDelBus`: el único que crea versiones de esquema es este proyecto, y hacerlo
        // pasa por la puerta de compatibilidad. Con el registro automático, cambiar un `.avsc` y
        // arrancar publicaría una versión nueva sin que nadie la mirase.
        ajustes.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, false);

        // `acks=all` + productor idempotente: sin lo primero se pierde lo escrito en cuanto caiga el
        // líder de la partición; sin lo segundo, un reintento interno del cliente puede duplicar y
        // —lo que importa aquí— DESORDENAR los mensajes de una misma clave, que es justo la
        // garantía por la que la clave es el paciente.
        ajustes.put(ProducerConfig.ACKS_CONFIG, "all");
        ajustes.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Con el broker caído, `send()` se queda esperando metadatos hasta `max.block.ms`, que por
        // defecto es un minuto. Aquí eso significaría un hilo del planificador bloqueado un minuto
        // por vuelta: el relay tiene que rendirse pronto y volver a intentarlo, que para eso el
        // hecho sigue en el outbox.
        ajustes.put(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) bus.esperaDeEnvio().toMillis());

        return new DefaultKafkaProducerFactory<>(ajustes);
    }

    @Bean
    KafkaTemplate<String, SpecificRecord> plantillaDeHechos(ProducerFactory<String, SpecificRecord> productor) {
        return new KafkaTemplate<>(productor);
    }

    /**
     * El cliente del registro.
     *
     * <p>Se construye con la fábrica de Confluent y no con {@code new CachedSchemaRegistryClient(…)}
     * porque es la que entiende el esquema {@code mock://}, que es como los tests usan un registro en
     * memoria sin levantar un servidor. Con el constructor directo, ese caso no existiría.
     */
    @Bean
    SchemaRegistryClient clienteDelRegistroDeEsquemas(PropiedadesDelBus bus) {
        List<SchemaProvider> soloAvro = List.of(new AvroSchemaProvider());
        return SchemaRegistryClientFactory.newClient(
                List.of(bus.registroDeEsquemas()), Topico.values().length, soloAvro, Map.of(), null);
    }

    @Bean
    BandejaDeSalida bandejaDeSalida(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        return new BandejaDeSalida(jdbc, json);
    }

    @Bean
    EsquemasDelBus esquemasDelBus(SchemaRegistryClient registro) {
        return new EsquemasDelBus(registro);
    }

    @Bean
    RelayDelOutbox relayDelOutbox(
            BandejaDeSalida bandeja,
            EsquemasDelBus esquemas,
            KafkaTemplate<String, SpecificRecord> plantilla,
            PropiedadesDelBus bus) {
        return new RelayDelOutbox(bandeja, esquemas, plantilla, bus.tanda(), bus.esperaDeEnvio());
    }
}
