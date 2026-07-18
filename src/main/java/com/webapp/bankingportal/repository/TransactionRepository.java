package com.webapp.bankingportal.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.webapp.bankingportal.entity.Transaction;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Loads every transaction for an account, fetching {@code sourceAccount}
     * and {@code targetAccount} in the same query via {@code JOIN FETCH}.
     *
     * <p>
     * {@code Transaction.sourceAccount}/{@code targetAccount} are
     * {@code FetchType.LAZY}. Without the fetch joins here, mapping N
     * transactions to {@code TransactionDTO} (which reads
     * {@code getSourceAccount().getAccountNumber()}) would trigger up to 2N
     * extra round trips to the database - the classic N+1 problem. With the
     * fetch joins, the whole history is loaded in a single query regardless
     * of how many transactions the account has.
     */
    @Query("SELECT t FROM Transaction t "
            + "LEFT JOIN FETCH t.sourceAccount "
            + "LEFT JOIN FETCH t.targetAccount "
            + "WHERE t.sourceAccount.accountNumber = :accountNumber "
            + "   OR t.targetAccount.accountNumber = :accountNumber")
    List<Transaction> findBySourceAccount_AccountNumberOrTargetAccount_AccountNumber(
            @Param("accountNumber") String sourceAccountNumber,
            @Param("accountNumber") String targetAccountNumber);

    /**
     * Paginated equivalent of the query above, for accounts with a long
     * transaction history where loading every row at once would be wasteful.
     * Newest transactions first.
     */
    @Query(value = "SELECT t FROM Transaction t "
            + "LEFT JOIN FETCH t.sourceAccount "
            + "LEFT JOIN FETCH t.targetAccount "
            + "WHERE t.sourceAccount.accountNumber = :accountNumber "
            + "   OR t.targetAccount.accountNumber = :accountNumber "
            + "ORDER BY t.transactionDate DESC",
            countQuery = "SELECT COUNT(t) FROM Transaction t "
                    + "WHERE t.sourceAccount.accountNumber = :accountNumber "
                    + "   OR t.targetAccount.accountNumber = :accountNumber")
    Page<Transaction> findByAccountNumberPaged(@Param("accountNumber") String accountNumber, Pageable pageable);
}
