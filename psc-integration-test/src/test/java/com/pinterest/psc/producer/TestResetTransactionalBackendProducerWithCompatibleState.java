package com.pinterest.psc.producer;

import com.pinterest.psc.common.MessageId;
import com.pinterest.psc.common.TestUtils;
import com.pinterest.psc.common.TopicUri;
import com.pinterest.psc.config.PscConfiguration;
import com.pinterest.psc.exception.producer.ProducerException;
import com.pinterest.psc.exception.startup.ConfigurationException;
import com.pinterest.psc.integration.KafkaCluster;
import com.pinterest.psc.serde.StringSerializer;
import com.pinterest.psc.utils.PscTestUtils;
import com.salesforce.kafka.test.junit5.SharedKafkaTestResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestResetTransactionalBackendProducerWithCompatibleState {
    @RegisterExtension
    public static final SharedKafkaTestResource sharedKafkaTestResource = new SharedKafkaTestResource()
            .withBrokers(1)
            .withBrokerProperty("auto.create.topics.enable", "false");

    private static final int TEST_TIMEOUT_SECONDS = 10;
    private static final PscConfiguration producerConfiguration = new PscConfiguration();
    private static String baseProducerClientId;
    private static final String topic1 = "topic1";
    private static final int partitions1 = 12;
    private KafkaCluster kafkaCluster;
    private String topicUriStr1;

    /**
     * Initializes a Kafka cluster that is commonly used by all tests, and creates a single topic on it.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        baseProducerClientId = this.getClass().getSimpleName() + "-psc-producer-client";
        producerConfiguration.clear();
        producerConfiguration.setProperty(PscConfiguration.PSC_METRICS_REPORTER_CLASS, TestUtils.DEFAULT_METRICS_REPORTER);
        producerConfiguration.setProperty(PscConfiguration.PSC_PRODUCER_CLIENT_ID, baseProducerClientId + "-" + UUID.randomUUID());
        producerConfiguration.setProperty(PscConfiguration.PSC_CONFIG_LOGGING_ENABLED, "false");

        int port = sharedKafkaTestResource.getKafkaTestUtils().describeClusterNodes().iterator().next().port();
        kafkaCluster = new KafkaCluster("plaintext", "region", "cluster", port);
        topicUriStr1 = String.format("%s:%s%s:kafka:env:cloud_%s::%s:%s",
                kafkaCluster.getTransport(), TopicUri.SEPARATOR, TopicUri.STANDARD, kafkaCluster.getRegion(), kafkaCluster.getCluster(), topic1);

        PscTestUtils.createTopicAndVerify(sharedKafkaTestResource, topic1, partitions1);
    }

    /**
     * Deleted the topic that is created by default. Also, adds a slight delay to make sure cleanup is complete
     * when tests run consecutively.
     *
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @AfterEach
    public void tearDown() throws ExecutionException, InterruptedException {
        PscTestUtils.deleteTopicAndVerify(sharedKafkaTestResource, topic1);
        Thread.sleep(1000);
    }

    /**
     * Verifies that resetting a transactional backend producer that is in a compatible transactional state works as
     * expected. This means the backend producer is reset and the transaction is not interrupted.
     *
     * @throws ConfigurationException
     * @throws ProducerException
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Timeout(TEST_TIMEOUT_SECONDS)
    @ParameterizedTest
    @EnumSource(value = PscProducer.TransactionalState.class, names = {"NON_TRANSACTIONAL", "INIT_AND_BEGUN", "READY"})
    public void testResetBackendProducerOfTransactionalPscProducerWithACompatibleState(PscProducer.TransactionalState transactionalState) throws ConfigurationException, ProducerException, ExecutionException, InterruptedException {
        producerConfiguration.setProperty(PscConfiguration.PSC_PRODUCER_KEY_SERIALIZER, StringSerializer.class.getName());
        producerConfiguration.setProperty(PscConfiguration.PSC_PRODUCER_VALUE_SERIALIZER, StringSerializer.class.getName());
        producerConfiguration.setProperty(PscConfiguration.PSC_PRODUCER_TRANSACTIONAL_ID, "transactional-psc-producer");
        producerConfiguration.setProperty(PscConfiguration.PSC_PRODUCER_TRANSACTION_TIMEOUT_MS, "60000");
        producerConfiguration.setProperty(PscConfiguration.PSC_METRICS_REPORTER_CLASS, TestUtils.DEFAULT_METRICS_REPORTER);
        producerConfiguration.setProperty(PscConfiguration.PSC_CONFIG_LOGGING_ENABLED, "false");
        producerConfiguration.setProperty("psc.producer.request.timeout.ms", "3000");
        PscProducer<String, String> pscProducer = new PscProducer<>(producerConfiguration);

        // do an initial set of sends
        PscProducerMessage<String, String> pscProducerMessage = new PscProducerMessage<>(
                topicUriStr1, "key", "value", System.currentTimeMillis()
        );

        PscBackendProducer backendProducer1 = null;
        pscProducer.beginTransaction();
        switch (transactionalState) {
            case NON_TRANSACTIONAL:
                pscProducer.abortTransaction();
                assertEquals(0, PscProducerUtils.getBackendProducersOf(pscProducer).size());
                break;
            case INIT_AND_BEGUN:
                assertEquals(0, PscProducerUtils.getBackendProducersOf(pscProducer).size());
                break;
            case READY:
                pscProducer.send(pscProducerMessage);
                pscProducer.commitTransaction();
                assertEquals(1, PscProducerUtils.getBackendProducersOf(pscProducer).size());
                backendProducer1 = PscProducerUtils.getBackendProducersOf(pscProducer).iterator().next();
                break;
            default:
                fail("Unexpected transaction state: " + transactionalState);
        }

        // reset backend producer
        PscProducerUtils.resetBackendProducer(pscProducer, backendProducer1);

        switch (transactionalState) {
            case NON_TRANSACTIONAL:
            case READY:
                pscProducer.beginTransaction();
                break;
            default: // INIT_AND_BEGUN
                // no-op
        }

        // do more sends and verify a new backend producer is successfully producing
        Future<MessageId> future;
        pscProducerMessage = new PscProducerMessage<>(topicUriStr1, "key", "value", System.currentTimeMillis());
        future = pscProducer.send(pscProducerMessage);
        pscProducer.commitTransaction();
        assertNotNull(future);
        MessageId messageId = future.get();
        assertNotNull(messageId);
        assertNotNull(messageId.getTopicUriPartition());
        assertTrue(messageId.getOffset() >= 0);

        assertEquals(1, PscProducerUtils.getBackendProducersOf(pscProducer).size());
        PscBackendProducer backendProducer2 = PscProducerUtils.getBackendProducersOf(pscProducer).iterator().next();
        assertNotEquals(backendProducer1, backendProducer2);

        pscProducer.close();
    }

}
