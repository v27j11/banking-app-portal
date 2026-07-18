package com.webapp.bankingportal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.webapp.bankingportal.entity.Account;
import com.webapp.bankingportal.entity.TransactionType;
import com.webapp.bankingportal.entity.User;
import com.webapp.bankingportal.event.TransactionEvent;
import com.webapp.bankingportal.messaging.TransactionEventConsumer;
import com.webapp.bankingportal.repository.AccountRepository;
import com.webapp.bankingportal.service.EmailService;

@ExtendWith(MockitoExtension.class)
class TransactionEventConsumerTests {

    @Mock
    private EmailService emailService;

    @Mock
    private AccountRepository accountRepository;

    private TransactionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransactionEventConsumer(emailService, accountRepository);
    }

    private Account accountWithEmail(String accountNumber, String email) {
        User user = new User();
        user.setEmail(email);

        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setUser(user);
        return account;
    }

    @Test
    void depositEvent_sendsNotificationToSourceAccountOnly() {
        TransactionEvent event = new TransactionEvent(
                1L, TransactionType.CASH_DEPOSIT, 100.0, new Date(), "SRC001", null);

        when(accountRepository.findByAccountNumber("SRC001"))
                .thenReturn(accountWithEmail("SRC001", "src@example.com"));
        when(emailService.sendEmail(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        consumer.onTransactionEvent(event);

        verify(emailService, times(1)).sendEmail(eq("src@example.com"), anyString(), anyString());
    }

    @Test
    void transferEvent_sendsNotificationToBothSourceAndTargetAccounts() {
        TransactionEvent event = new TransactionEvent(
                2L, TransactionType.CASH_TRANSFER, 250.0, new Date(), "SRC001", "TGT002");

        when(accountRepository.findByAccountNumber("SRC001"))
                .thenReturn(accountWithEmail("SRC001", "src@example.com"));
        when(accountRepository.findByAccountNumber("TGT002"))
                .thenReturn(accountWithEmail("TGT002", "tgt@example.com"));
        when(emailService.sendEmail(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        consumer.onTransactionEvent(event);

        verify(emailService, times(1)).sendEmail(eq("src@example.com"), anyString(), anyString());
        verify(emailService, times(1)).sendEmail(eq("tgt@example.com"), anyString(), anyString());
    }

    @Test
    void accountNotFound_doesNotThrowAndSkipsNotification() {
        TransactionEvent event = new TransactionEvent(
                3L, TransactionType.CASH_WITHDRAWAL, 50.0, new Date(), "GHOST001", null);

        when(accountRepository.findByAccountNumber("GHOST001")).thenReturn(null);

        consumer.onTransactionEvent(event);

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }
}
