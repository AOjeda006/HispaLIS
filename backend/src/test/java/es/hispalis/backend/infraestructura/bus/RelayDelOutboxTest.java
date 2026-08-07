package es.hispalis.backend.infraestructura.bus;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import es.hispalis.backend.TestDeIntegracion;
import es.hispalis.backend.fhir.CircuitoDePrueba;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El relay del {@code outbox} contra un Kafka de verdad.
 *
 * <h2>Por qué un broker real y no un doble</h2>
 *
 * <p>Lo que hay que demostrar aquí es que <strong>con el bus caído la API sigue funcionando</strong>
 * y que lo pendiente sale solo cuando el bus vuelve. Un doble en memoria no se cae de la misma
 * manera: no hay <em>timeout</em> de metadatos, no hay particiones y no hay serialización, que es
 * justo donde vive el contrato. Así que el broker es Kafka, en proceso y sin Docker — la misma
 * decisión que el PostgreSQL embebido, por el mismo motivo.
 *
 * <p>El registro de esquemas sí es en memoria ({@code mock://}). No hay forma de levantar el servidor
 * de Confluent en proceso sin arrastrar media pila HTTP, y lo que el registro decide —si una versión
 * rompe a los consumidores— se prueba aparte, en {@code CompatibilidadDeEsquemasTest}, con su mismo
 * comprobador.
 *
 * <h2>Por qué el relay se llama a mano</h2>
 *
 * <p>El intervalo va a una hora y las vueltas las da el test llamando a {@link RelayDelOutbox#drenar()},
 * que es <em>el mismo método</em> que invoca el planificador. Con el temporizador suelto, este
 * contexto —que Spring cachea y mantiene vivo el resto de la ejecución— seguiría drenando el
 * {@code outbox} compartido mientras corren otras clases de test, y {@code OutboxTransaccionalTest}
 * fallaría de vez en cuando por una fila que le marcó otro. Un test que falla una de cada veinte
 * veces no prueba nada y acaba desactivado.
 *
 * <h2>Por qué estos tests van en orden</h2>
 *
 * <p>Porque el broker tiene un ciclo de vida y «todavía no está» es un estado por el que solo se
 * pasa una vez. El primero es el que lo comprueba y el que lo levanta; los demás se lo encuentran
 * puesto. Ordenar tests suele ser un olor, y aquí es la alternativa honesta a levantar y tirar un
 * Kafka por método —siete segundos cada vez— para simular algo que ya está simulado.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "hispalis.bus.habilitado=true",
            "hispalis.bus.registro-de-esquemas=mock://" + RelayDelOutboxTest.AMBITO,
            "hispalis.bus.intervalo=PT1H",
            "hispalis.bus.espera-de-envio=PT3S",
            "hispalis.bus.tanda=200"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RelayDelOutboxTest extends TestDeIntegracion {

    static final String AMBITO = "relay-del-outbox";

    /** Más de una para que la clave de partición signifique algo: con una sola, ordenar es gratis. */
    private static final int PARTICIONES = 3;

    private static final String[] TOPICOS = {
        "lab.peticiones.v1", "lab.especimenes.v1", "lab.resultados.v1", "lab.informes.v1"
    };

    /**
     * El puerto se reserva <strong>antes</strong> de arrancar el contexto porque la aplicación tiene
     * que apuntar a un broker que todavía no existe: ese es el escenario que se quiere probar.
     */
    private static final int PUERTO = puertoLibre();

    private static EmbeddedKafkaBroker broker;

    private final FhirContext contexto = FhirContext.forR5();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private RelayDelOutbox relay;

    private CircuitoDePrueba circuito;

    @DynamicPropertySource
    static void apuntarAlBrokerQueTodaviaNoEsta(DynamicPropertyRegistry registro) {
        registro.add("spring.kafka.bootstrap-servers", () -> "localhost:" + PUERTO);
    }

    @BeforeEach
    void prepararElCircuito() {
        circuito = new CircuitoDePrueba(rest, contexto);
    }

    /**
     * La prueba del outbox, entera y en orden: el broker caído no impide escribir, y al levantarlo se
     * entrega lo que quedó pendiente.
     *
     * <p>Va en un solo test porque es una sola historia y partirla en dos dejaría la mitad
     * dependiendo de que la otra hubiera corrido antes. Si un {@code POST} fallara porque Kafka está
     * caído, el outbox no estaría haciendo su trabajo — que es exactamente para lo que está.
     */
    @Test
    @Order(1)
    void con_el_broker_caido_se_sigue_escribiendo_y_al_levantarlo_se_entrega() {
        String paciente = recorrerElCircuito();

        // Una vuelta del relay sin bus al otro lado. No revienta: se traga el fallo y lo reintentará.
        relay.drenar();

        assertThat(publicadosDe(paciente))
                .as("no hay broker: no puede haberse publicado nada")
                .isZero();
        // Se cuentan solo los hechos con tópico. El sexto es la filiación, que se descarta sin
        // necesitar el bus, y si ya la descartó o no depende de si el `outbox` —compartido con las
        // demás clases de test— traía trabajo anterior que cortó la vuelta antes de llegar a ella.
        assertThat(pendientesConTopicoDe(paciente))
                .as("los cinco hechos con tópico siguen esperando: sin bus no sale ninguno")
                .isEqualTo(5);

        arrancarElBrokerSiHaceFalta();
        vaciarElOutbox();

        assertThat(publicadosDe(paciente)).isEqualTo(5);
        assertThat(hechosRecibidosDe(paciente))
                .as("y llegaron de verdad al bus, no solo se marcaron en la base")
                .hasSize(5);
    }

    /** Cada hecho a su tópico, y el tópico queda anotado en la fila: el outbox dice qué salió y dónde. */
    @Test
    void cada_hecho_va_al_topico_que_le_toca() {
        String paciente = recorrerElCircuito();
        arrancarElBrokerSiHaceFalta();
        vaciarElOutbox();

        assertThat(topicosDe(paciente))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "PETICION_REGISTRADA", "lab.peticiones.v1",
                        "ESPECIMEN_REGISTRADO", "lab.especimenes.v1",
                        "RESULTADO_INFORMADO", "lab.resultados.v1",
                        "RESULTADO_VALIDADO", "lab.resultados.v1",
                        "INFORME_EMITIDO", "lab.informes.v1"));
    }

    /**
     * El invariante 6 sobre los <strong>bytes que salen</strong>, no sobre la carga antes de
     * serializarla.
     *
     * <p>{@code OutboxTransaccionalTest} ya comprueba que la carga no lleva PHI; esto comprueba lo
     * otro: que nadie ha metido nada por el camino —una cabecera, la clave, un campo del esquema— y
     * que lo que un consumidor cualquiera vería en el tópico son referencias. Se leen los bytes en
     * crudo, sin deserializar, porque deserializar solo enseñaría los campos que el esquema declara.
     */
    @Test
    void por_el_topico_no_sale_nada_que_identifique_a_la_persona() {
        String nhc = CircuitoDePrueba.siguienteNhc();
        recorrerElCircuito(nhc, CircuitoDePrueba.APELLIDOS);
        arrancarElBrokerSiHaceFalta();
        vaciarElOutbox();

        List<String> mensajes = mensajesEnCrudo();

        assertThat(mensajes).isNotEmpty();
        assertThat(mensajes)
                .as("un tópico replicado es lo más difícil de borrar que hay: aquí no entra PHI")
                .noneMatch(mensaje -> mensaje.contains(nhc)
                        || mensaje.contains(CircuitoDePrueba.APELLIDOS)
                        || mensaje.contains(CircuitoDePrueba.NOMBRE_DE_PILA)
                        || mensaje.contains(CircuitoDePrueba.DNI)
                        || mensaje.contains(CircuitoDePrueba.NUHSA));
    }

    /**
     * Lo de cada paciente, en su partición y en su orden.
     *
     * <p>Se escriben dos pacientes <strong>intercalados</strong>: si el reparto fuera por otra cosa
     * —o por nada, que es lo que pasa con la clave a nulo—, los hechos de los dos se repartirían
     * entre las tres particiones y un consumidor podría aplicar la validación de un resultado antes
     * que el resultado. Con tres particiones y dos claves, que cada persona caiga siempre en la misma
     * es lo que hay que ver.
     *
     * <p><strong>El orden es por tópico, no entre tópicos.</strong> Kafka ordena dentro de una
     * partición, y la partición la determinan el tópico <em>y</em> la clave: la muestra y el
     * resultado de la misma persona viajan por tópicos distintos y entre ellos no hay orden
     * garantizado. La garantía que el diseño necesita —«no aplicar una validación antes que el
     * resultado que valida»— sí se cumple, porque los dos son hechos de resultado y comparten tópico.
     * Un consumidor que necesite cruzar tópicos tiene que resolverlo él, y conviene que esté escrito
     * aquí y no que se descubra en producción.
     */
    @Test
    void los_hechos_de_un_paciente_van_a_una_sola_particion_y_en_orden() {
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String uno = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        String otro = circuito.crear(
                CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc(), CircuitoDePrueba.OTROS_APELLIDOS));

        circuito.crear(CircuitoDePrueba.linea(uno, laboratorio));
        circuito.crear(CircuitoDePrueba.linea(otro, laboratorio));
        String muestraDeUno = circuito.crear(CircuitoDePrueba.muestra(uno));
        String muestraDeOtro = circuito.crear(CircuitoDePrueba.muestra(otro));
        String resultadoDeUno =
                circuito.crear(CircuitoDePrueba.resultado(uno, muestraDeUno, lineaDe(uno), laboratorio));
        String resultadoDeOtro =
                circuito.crear(CircuitoDePrueba.resultado(otro, muestraDeOtro, lineaDe(otro), laboratorio));
        circuito.validar(resultadoDeUno);
        circuito.validar(resultadoDeOtro);

        arrancarElBrokerSiHaceFalta();
        vaciarElOutbox();

        for (String paciente : List.of(uno, otro)) {
            List<ConsumerRecord<String, Object>> suyos = hechosRecibidosDe(paciente);

            assertThat(suyos).as("línea, muestra, resultado y firma").hasSize(4);
            assertThat(suyos).extracting(ConsumerRecord::key).containsOnly(CircuitoDePrueba.identidadDe(paciente));

            for (String topico : TOPICOS) {
                assertThat(particionesUsadasEn(suyos, topico))
                        .as("en %s, todo lo de una persona en la misma partición: es lo único que ordena", topico)
                        .hasSizeLessThanOrEqualTo(1);
            }
            assertThat(ordenDeLoRecibidoEn(suyos, "lab.resultados.v1"))
                    .as("la firma nunca llega antes que la cifra que firma")
                    .containsExactly("RESULTADO_INFORMADO", "RESULTADO_VALIDADO");
        }
    }

    /**
     * Al menos una vez, probado por repetición.
     *
     * <p>Se simula lo único que puede pasar de verdad: el relay publicó y el proceso se cayó antes de
     * marcar la fila, así que la vuelta siguiente la vuelve a publicar. Se hace poniendo
     * {@code publicado_en} otra vez a nulo, que es exactamente el estado en el que se habría quedado
     * la base. El consumidor tiene que acabar igual que si hubiera llegado una sola vez.
     */
    @Test
    void reentregar_los_mismos_hechos_no_cambia_el_estado_del_consumidor() {
        String paciente = recorrerElCircuito();
        arrancarElBrokerSiHaceFalta();
        vaciarElOutbox();

        ConsumidorIdempotente consumidor = new ConsumidorIdempotente();
        aplicarTodoLoRecibido(consumidor);
        int trasLaPrimeraEntrega = consumidor.hechosDe(CircuitoDePrueba.identidadDe(paciente));
        List<String> ordenTrasLaPrimera = List.copyOf(consumidor.ordenDe(CircuitoDePrueba.identidadDe(paciente)));

        // Como si el proceso se hubiera caído entre publicar y marcar. Dos veces, para que nadie
        // pueda decir que salió bien por casualidad.
        for (int vuelta = 0; vuelta < 2; vuelta++) {
            desmarcarLoPublicadoDe(paciente);
            vaciarElOutbox();
        }

        assertThat(publicadosDe(paciente)).isEqualTo(5);
        aplicarTodoLoRecibido(consumidor);

        assertThat(consumidor.hechosDe(CircuitoDePrueba.identidadDe(paciente)))
                .as("tres entregas del mismo hecho, un solo efecto")
                .isEqualTo(trasLaPrimeraEntrega);
        assertThat(consumidor.ordenDe(CircuitoDePrueba.identidadDe(paciente))).isEqualTo(ordenTrasLaPrimera);
    }

    /** La filiación no tiene tópico y no lo va a tener: se cierra la fila y se dice por qué. */
    @Test
    void la_filiacion_del_paciente_no_sale_al_bus() {
        String paciente = circuito.crear(CircuitoDePrueba.paciente(CircuitoDePrueba.siguienteNhc()));
        arrancarElBrokerSiHaceFalta();
        vaciarElOutbox();

        Map<String, Object> hecho = unicoHechoDe(paciente);

        assertThat(hecho.get("tipo")).isEqualTo("PACIENTE_REGISTRADO");
        assertThat(hecho.get("descartado_en")).isNotNull();
        assertThat(hecho.get("publicado_en")).isNull();
        assertThat(hecho.get("topico")).isNull();
    }

    // ── El escenario ─────────────────────────────────────────────────────────────────────────────

    private String recorrerElCircuito() {
        return recorrerElCircuito(CircuitoDePrueba.siguienteNhc(), CircuitoDePrueba.APELLIDOS);
    }

    private String recorrerElCircuito(String nhc, String apellidos) {
        String laboratorio = circuito.crear(CircuitoDePrueba.laboratorio());
        String paciente = circuito.crear(CircuitoDePrueba.paciente(nhc, apellidos));
        String linea = circuito.crear(CircuitoDePrueba.linea(paciente, laboratorio));
        String muestra = circuito.crear(CircuitoDePrueba.muestra(paciente));
        String resultado = circuito.crear(CircuitoDePrueba.resultado(paciente, muestra, linea, laboratorio));
        circuito.validar(resultado);
        circuito.crear(CircuitoDePrueba.informe(paciente, laboratorio, resultado));
        return paciente;
    }

    /** La referencia de la única línea de un paciente, que es lo que el resultado necesita. */
    private String lineaDe(String paciente) {
        return "ServiceRequest/"
                + jdbc.queryForObject(
                                """
                        SELECT carga ->> 'serviceRequestRef'
                          FROM outbox.hecho
                         WHERE clave_de_particion = :paciente AND tipo = 'PETICION_REGISTRADA'
                        """,
                                new MapSqlParameterSource(
                                        "paciente", UUID.fromString(CircuitoDePrueba.identidadDe(paciente))),
                                String.class)
                        .substring("ServiceRequest/".length());
    }

    // ── El bus ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Arranca el broker en el puerto reservado. Idempotente y sin {@code destroy()} en un
     * {@code @AfterAll}: el contexto de Spring sobrevive a esta clase, y dejar al relay hablando con
     * un broker que ya no está solo llenaría el log del resto de la ejecución. Se cierra con la JVM,
     * igual que el PostgreSQL embebido.
     *
     * <p><strong>Por qué el broker de ZooKeeper y no el de KRaft.</strong> Es el único de los dos que
     * respeta el puerto que se le pide: {@code EmbeddedKafkaKraftBroker.kafkaPorts(…)} se ignora —el
     * banco de pruebas de KRaft elige el suyo— y comprobado aquí, pedir el 40245 abría el 40247. Y
     * fijar el puerto es justo lo que permite que la aplicación arranque apuntando a un broker que
     * todavía no existe, que es el escenario del test. Kafka 4 retira ZooKeeper: cuando se suba,
     * habrá que buscar otra forma de fijar el puerto, y está anotado en {@code docs/PLAN.md}.
     */
    private static synchronized void arrancarElBrokerSiHaceFalta() {
        if (broker != null) {
            return;
        }
        // Sin tópicos en el constructor: se crean después y tolerando que ya estén. El broker
        // embebido guarda su log en un temporal que en Windows no siempre se borra al terminar la
        // JVM —se ve en el log: «Error deleting …\kafka-…»—, así que una segunda ejecución se
        // encuentra los tópicos de la primera y `afterPropertiesSet` reventaría con
        // `TopicExistsException`. Peor aún: reventaría DESPUÉS de abrir el puerto, dejando la
        // referencia a nulo y el puerto ocupado, y los tests siguientes fallarían con un error que
        // no se parece en nada a la causa.
        EmbeddedKafkaZKBroker arrancando = new EmbeddedKafkaZKBroker(1, true, PARTICIONES);
        arrancando.kafkaPorts(PUERTO);
        arrancando.afterPropertiesSet();
        broker = arrancando;
        Runtime.getRuntime().addShutdownHook(new Thread(arrancando::destroy));

        // El mapa trae una entrada por tópico con el fallo dentro, o `null` si se creó bien.
        arrancando.addTopicsWithResults(TOPICOS).forEach((topico, fallo) -> {
            if (fallo != null && !(fallo.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("No se pudo crear el tópico " + topico, fallo);
            }
        });
    }

    /** Vueltas del relay hasta que no quede nada por sacar. */
    private void vaciarElOutbox() {
        for (int vuelta = 0; vuelta < 20 && quedaAlgoPendiente(); vuelta++) {
            relay.drenar();
        }
        assertThat(quedaAlgoPendiente())
                .as("el relay no consiguió vaciar el outbox en veinte vueltas")
                .isFalse();
    }

    private void aplicarTodoLoRecibido(ConsumidorIdempotente consumidor) {
        for (ConsumerRecord<String, Object> recibido : consumirTodo(deserializadorAvro())) {
            consumidor.aplicar((GenericRecord) recibido.value());
        }
    }

    private List<ConsumerRecord<String, Object>> hechosRecibidosDe(String paciente) {
        String pacienteId = CircuitoDePrueba.identidadDe(paciente);
        return consumirTodo(deserializadorAvro()).stream()
                .filter(recibido -> pacienteId.equals(recibido.key()))
                .toList();
    }

    private static List<String> ordenDeLoRecibidoEn(List<ConsumerRecord<String, Object>> recibidos, String topico) {
        return recibidos.stream()
                .filter(recibido -> recibido.topic().equals(topico))
                .sorted(java.util.Comparator.comparingLong(ConsumerRecord::offset))
                .map(recibido -> ((GenericRecord) recibido.value()).get("tipo").toString())
                .toList();
    }

    private static List<Integer> particionesUsadasEn(List<ConsumerRecord<String, Object>> recibidos, String topico) {
        return recibidos.stream()
                .filter(recibido -> recibido.topic().equals(topico))
                .map(ConsumerRecord::partition)
                .distinct()
                .toList();
    }

    /** Lo que hay en los cuatro tópicos, tal cual viaja, sin pasar por el esquema. */
    private List<String> mensajesEnCrudo() {
        Map<String, Object> ajustes = new HashMap<>();
        ajustes.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return consumirTodo(ajustes).stream()
                .map(recibido -> recibido.key() + " " + new String((byte[]) recibido.value(), StandardCharsets.UTF_8))
                .toList();
    }

    private static Map<String, Object> deserializadorAvro() {
        Map<String, Object> ajustes = new HashMap<>();
        ajustes.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        ajustes.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://" + AMBITO);
        ajustes.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        return ajustes;
    }

    /**
     * Lee los cuatro tópicos desde el principio, con un grupo nuevo cada vez. Un consumidor de verdad
     * llevaría su desplazamiento; aquí se quiere ver <strong>todo</strong> lo publicado en cada
     * comprobación, incluida la reentrega.
     */
    private List<ConsumerRecord<String, Object>> consumirTodo(Map<String, Object> ajustesPropios) {
        Map<String, Object> ajustes =
                KafkaTestUtils.consumerProps(broker.getBrokersAsString(), "lectura-" + UUID.randomUUID(), "false");
        ajustes.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        ajustes.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        ajustes.putAll(ajustesPropios);

        List<ConsumerRecord<String, Object>> todo = new ArrayList<>();
        try (Consumer<String, Object> consumidor =
                new DefaultKafkaConsumerFactory<String, Object>(ajustes).createConsumer()) {
            broker.consumeFromEmbeddedTopics(consumidor, TOPICOS);
            while (true) {
                ConsumerRecords<String, Object> lote = KafkaTestUtils.getRecords(consumidor, Duration.ofSeconds(2));
                if (lote.isEmpty()) {
                    return todo;
                }
                lote.forEach(todo::add);
            }
        }
    }

    // ── La base ──────────────────────────────────────────────────────────────────────────────────

    private boolean quedaAlgoPendiente() {
        Integer cuantos = jdbc.getJdbcTemplate()
                .queryForObject(
                        "SELECT count(*) FROM outbox.hecho WHERE publicado_en IS NULL AND descartado_en IS NULL",
                        Integer.class);
        return cuantos != null && cuantos > 0;
    }

    private int publicadosDe(String paciente) {
        return contar("publicado_en IS NOT NULL", paciente);
    }

    private int pendientesConTopicoDe(String paciente) {
        return contar("publicado_en IS NULL AND tipo NOT LIKE 'PACIENTE%'", paciente);
    }

    private int contar(String condicion, String paciente) {
        Integer cuantos = jdbc.queryForObject(
                "SELECT count(*) FROM outbox.hecho WHERE clave_de_particion = :paciente AND " + condicion,
                parametroDe(paciente),
                Integer.class);
        return cuantos == null ? 0 : cuantos;
    }

    private Map<String, String> topicosDe(String paciente) {
        Map<String, String> porTipo = new HashMap<>();
        jdbc.queryForList(
                        """
                        SELECT tipo, topico FROM outbox.hecho
                         WHERE clave_de_particion = :paciente AND topico IS NOT NULL
                        """,
                        parametroDe(paciente))
                .forEach(fila -> porTipo.put((String) fila.get("tipo"), (String) fila.get("topico")));
        return porTipo;
    }

    private Map<String, Object> unicoHechoDe(String paciente) {
        List<Map<String, Object>> hechos = jdbc.queryForList(
                "SELECT tipo, publicado_en, descartado_en, topico FROM outbox.hecho WHERE clave_de_particion = :paciente",
                parametroDe(paciente));
        assertThat(hechos).hasSize(1);
        return hechos.get(0);
    }

    private void desmarcarLoPublicadoDe(String paciente) {
        jdbc.update(
                "UPDATE outbox.hecho SET publicado_en = NULL, topico = NULL WHERE clave_de_particion = :paciente",
                parametroDe(paciente));
    }

    private static MapSqlParameterSource parametroDe(String paciente) {
        return new MapSqlParameterSource("paciente", UUID.fromString(CircuitoDePrueba.identidadDe(paciente)));
    }

    private static int puertoLibre() {
        try (ServerSocket sondeo = new ServerSocket(0)) {
            return sondeo.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo reservar un puerto para el broker de pruebas", e);
        }
    }
}
