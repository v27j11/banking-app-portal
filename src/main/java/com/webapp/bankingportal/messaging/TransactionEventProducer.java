package com.webapp.bankingportal.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.webapp.bankingportal.config.KafkaConfig;
import com.webapp.bankingportal.event.TransactionEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges in-process transaction events to Kafka.
 *
 * <p>
 * Listens with {@code phase = AFTER_COMMIT}, which means the Kafka message
 * is only sent once the database transaction that created it has
 * successfully committed. If the deposit/withdrawal/transfer is rolled back
 * (e.g. insufficient balance), no event is published - downstream consumers
 * never see notifications for money movements that didn't actually happen.
 *
 * <p>
 * Publishing itself is fire-and-forget from the caller's perspective:
 * {@link KafkaTemplate#send} is asynchronous, and a failure to publish (e.g.
 * the Kafka broker is temporarily down) is logged but never propagated back
 * to the user-facing request, which has already completed successfully by
 * this point.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionEventProducer {

    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCommitted(TransactionEvent event) {
        publish(event);
    }

    private void publish(TransactionEvent event) {
        kafkaTemplate.send(KafkaConfig.TRANSACTION_EVENTS_TOPIC, event.getSourceAccountNumber(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish transaction event for transactionId={}: {}",
                                event.getTransactionId(), ex.getMessage());
                    } else {
                        log.debug("Published transaction event transactionId={} to partition={} offset={}",
                                event.getTransactionId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
