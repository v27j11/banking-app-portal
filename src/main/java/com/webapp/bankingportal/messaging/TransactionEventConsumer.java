package com.webapp.bankingportal.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.webapp.bankingportal.event.TransactionEvent;
import com.webapp.bankingportal.repository.AccountRepository;
import com.webapp.bankingportal.service.EmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Consumes {@link TransactionEvent}s from Kafka and turns them into
 * customer-facing notifications.
 *
 * <p>
 * This runs completely decoupled from the original HTTP request: the
 * deposit/withdraw/fund-transfer endpoints return to the client as soon as
 * the database transaction commits, while the (slower, less reliable, and
 * not business-critical) notification work happens here, in its own
 * consumer thread, with its own retry policy. A slow or temporarily-down
 * mail server can no longer add latency to - or fail - a money transfer.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final EmailService emailService;
    private final AccountRepository accountRepository;

    @KafkaListener(topics = "banking-portal.transaction-events", groupId = "${app.kafka.consumer-group:banking-portal-notifications}")
    public void onTransactionEvent(TransactionEvent event) {
        log.info("Processing transaction event: id={} type={} amount={} source={} target={}",
                event.getTransactionId(), event.getTransactionType(), event.getAmount(),
                event.getSourceAccountNumber(), event.getTargetAccountNumber());

        notifyAccountHolder(event.getSourceAccountNumber(), event);

        if (event.getTargetAccountNumber() != null) {
            notifyAccountHolder(event.getTargetAccountNumber(), event);
        }
    }

    private void notifyAccountHolder(String accountNumber, TransactionEvent event) {
        val account = accountRepository.findByAccountNumber(accountNumber);
        if (account == null || account.getUser() == null) {
            log.warn("Could not deliver transaction notification, account not found: {}", accountNumber);
            return;
        }

        String subject = "Transaction notification - " + event.getTransactionType();
        String body = buildNotificationBody(accountNumber, event);

        emailService.sendEmail(account.getUser().getEmail(), subject, body);
    }

    private String buildNotificationBody(String accountNumber, TransactionEvent event) {
        return "<div style=\"font-family: Arial, sans-serif; padding: 20px;\">"
                + "<h2>Transaction Notification</h2>"
                + "<p>Account: <strong>" + accountNumber + "</strong></p>"
                + "<p>Type: " + event.getTransactionType() + "</p>"
                + "<p>Amount: " + event.getAmount() + "</p>"
                + "<p>Date: " + event.getTransactionDate() + "</p>"
                + "<p>If you did not expect this activity, please contact support immediately.</p>"
                + "</div>";
    }
}
