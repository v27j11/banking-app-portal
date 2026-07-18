package com.webapp.bankingportal.entity;

import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Data;

/**
 * Transactions are queried almost exclusively by account (statement /
 * history views) and ordered/filtered by date, so both are indexed.
 * {@code source_account_id} and {@code target_account_id} back the
 * {@code findBySourceAccount_AccountNumberOrTargetAccount_AccountNumber}
 * lookup; without these, that query forces a full table scan once the
 * transactions table grows past a trivial size.
 */
@Entity
@Data
@Table(indexes = {
        @Index(name = "idx_transaction_source_account", columnList = "source_account_id"),
        @Index(name = "idx_transaction_target_account", columnList = "target_account_id"),
        @Index(name = "idx_transaction_date", columnList = "transactionDate")
})
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private double amount;

    @Enumerated(EnumType.STRING)
    private TransactionType transactionType;

    private Date transactionDate;

    // LAZY: callers that only need transaction amounts/dates (e.g. the Kafka
    // notification consumer building its own DTO) don't pay for an extra
    // join/query to load the full Account graph. Safe with Spring Boot's
    // default open-session-in-view, and the one place that DOES serialize
    // these to JSON (TransactionServiceImpl) already maps to TransactionDTO
    // within the service layer, before the session closes.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_account_id")
    private Account targetAccount;

}
