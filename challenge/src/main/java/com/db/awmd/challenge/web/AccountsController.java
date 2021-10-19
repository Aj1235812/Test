package com.db.awmd.challenge.web;

import com.db.awmd.challenge.domain.Account;
import com.db.awmd.challenge.exception.DuplicateAccountIdException;
import com.db.awmd.challenge.service.AccountsService;

import javax.validation.Valid;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

import com.db.awmd.challenge.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/v1/accounts")
@Slf4j
public class AccountsController {

    private final AccountsService accountsService;
    private final NotificationService notificationService;

    @Autowired
    public AccountsController(AccountsService accountsService, NotificationService notificationService) {
        this.accountsService = accountsService;
        this.notificationService = notificationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> createAccount(@RequestBody @Valid Account account) {
        log.info("Creating account {}", account);

        try {
            this.accountsService.createAccount(account);
        } catch (DuplicateAccountIdException daie) {
            return new ResponseEntity<>(daie.getMessage(), HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @GetMapping(path = "/{accountId}")
    public Account getAccount(@PathVariable String accountId) {
        log.info("Retrieving account for id {}", accountId);
        return this.accountsService.getAccount(accountId);
    }

    @PostMapping(path = "/transfer/{fromAccountId}/{toAccountId}/{amount}")
    public ResponseEntity<Object> transfer(@PathVariable @Valid @NotBlank @Size(max = 18) String fromAccountId, @PathVariable @Valid @NotBlank @Size(max = 18) String toAccountId, @PathVariable @Valid @Min(value = 1L, message = "The amount must be positive") BigDecimal amount) {
        Account fromAccount = accountsService.getAccount(fromAccountId);
        Account toAccount = accountsService.getAccount(toAccountId);
        ResponseEntity responseEntity = null;
        ReentrantLock lock = new ReentrantLock(true);
        lock.lock();
        boolean isAmountDedcuted = false;
        boolean isAmountTransferred = false;
        BigDecimal initialFromAmountBal=fromAccount.getBalance();
        BigDecimal initialtoAccountBal=toAccount.getBalance();
        if (fromAccount.getBalance().intValue() > amount.intValue()) {
            try {
                BigDecimal valFrom = fromAccount.getBalance().subtract(amount);
                fromAccount.setBalance(valFrom);
                isAmountDedcuted = true;

                BigDecimal valTo = toAccount.getBalance().add(amount);
                toAccount.setBalance(valTo);
                isAmountTransferred = true;
                responseEntity = new ResponseEntity(HttpStatus.OK);
                CompletableFuture
                        .runAsync(() -> {
                            notificationService.notifyAboutTransfer(fromAccount, "Amount " + amount + " has been transferred from your account to account " + toAccountId);
                            notificationService.notifyAboutTransfer(toAccount, "Amount " + amount + " has been credited to your account from account " + fromAccountId);
                        });

            } catch (Exception e) {
                log.error("Exception Occured during Transfer. Doing Rollback");
                if (isAmountDedcuted)
                    fromAccount.setBalance(initialFromAmountBal);
                if (isAmountTransferred)
                    toAccount.setBalance(initialtoAccountBal);
                responseEntity = new ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR);
            } finally {
                lock.unlock();
            }
        } else {
            responseEntity = new ResponseEntity(HttpStatus.BAD_REQUEST);
        }
        return responseEntity;
    }


}
