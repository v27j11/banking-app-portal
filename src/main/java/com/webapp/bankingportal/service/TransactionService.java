package com.webapp.bankingportal.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.webapp.bankingportal.dto.TransactionDTO;

public interface TransactionService {

	List<TransactionDTO> getAllTransactionsByAccountNumber(String accountNumber);

	/**
	 * Paginated transaction history, newest first. Preferred over
	 * {@link #getAllTransactionsByAccountNumber(String)} for accounts with a
	 * long history, since it avoids loading and serializing the entire
	 * history on every call.
	 */
	Page<TransactionDTO> getTransactionsByAccountNumber(String accountNumber, Pageable pageable);

	void sendBankStatementByEmail(String accountNumber);

}
