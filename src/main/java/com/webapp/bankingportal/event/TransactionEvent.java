package com.webapp.bankingportal.event;

import java.io.Serializable;
import java.util.Date;

import com.webapp.bankingportal.entity.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight, serializable payload published to Kafka whenever a
 * transaction (deposit, withdrawal or fund transfer) is committed to the
 * database.
 *
 * <p>
 * Consumers (e.g. the notification service) use this to do work that does
 * NOT need to happen inside the original request/response cycle - sending a
 * confirmation email, updating a fraud-detection model, feeding an analytics
 * pipeline, etc. Keeping this off the request thread keeps the
 * deposit/withdraw/transfer endpoints fast and lets notification failures
 * (e.g. a flaky mail server) never affect the money-moving operation itself.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEvent implements Serializable {

    private Long transactionId;
    private TransactionType transactionType;
    private double amount;
    private Date transactionDate;
    private String sourceAccountNumber;
    private String targetAccountNumber;
}
