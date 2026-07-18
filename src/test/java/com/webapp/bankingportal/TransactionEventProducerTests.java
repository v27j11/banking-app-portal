package com.webapp.bankingportal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.webapp.bankingportal.config.KafkaConfig;
import com.webapp.bankingportal.entity.TransactionType;
import com.webapp.bankingportal.event.TransactionEvent;
import com.webapp.bankingportal.messaging.TransactionEventProducer;

@ExtendWith(MockitoExtension.class)
class TransactionEventProducerTests {

    @Mock
    private KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    private TransactionEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new TransactionEventProducer(kafkaTemplate);
    }

    @Test
    void onTransactionCommitted_publishesToTransactionEventsTopicKeyedBySourceAccount() {
        TransactionEvent event = new TransactionEvent(
                1L, TransactionType.CASH_DEPOSIT, 500.0, new Date(), "SRC001", null);

        RecordMetadata recordMetadata = mock(RecordMetadata.class);
        SendResult<String, TransactionEvent> sendResult = new SendResult<>(null, recordMetadata);

        when(kafkaTemplate.send(eq(KafkaConfig.TRANSACTION_EVENTS_TOPIC), eq("SRC001"), eq(event)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        producer.onTransactionCommitted(event);

        verify(kafkaTemplate).send(eq(KafkaConfig.TRANSACTION_EVENTS_TOPIC), eq("SRC001"), eq(event));
    }

    @Test
    void onTransactionCommitted_brokerUnavailable_doesNotThrow() {
        TransactionEvent event = new TransactionEvent(
                2L, TransactionType.CASH_WITHDRAWAL, 200.0, new Date(), "SRC002", null);

        CompletableFuture<SendResult<String, TransactionEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker unavailable"));

        when(kafkaTemplate.send(eq(KafkaConfig.TRANSACTION_EVENTS_TOPIC), eq("SRC002"), eq(event)))
                .thenReturn(failedFuture);

        // Should not throw: a notification publish failure must never surface as
        // an error to whatever triggered the (already-committed) transaction.
        producer.onTransactionCommitted(event);
    }
}
