package com.nexor.payments.domain.model;

import java.util.Objects;

public record AccountId(String number, String branch, String bankCode) {
    public AccountId {
        Objects.requireNonNull(number, "Account number cannot be null");
        Objects.requireNonNull(bankCode, "Bank code cannot be null");
        if (number.isBlank() || bankCode.isBlank()) {
            throw new IllegalArgumentException("Account number and bank code cannot be blank");
        }
    }

    public static AccountId of(String number, String branch, String bankCode) {
        return new AccountId(number, branch, bankCode);
    }

    public static AccountId simple(String number) {
        return new AccountId(number, "0001", "NEXOR");
    }

    public static AccountId fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Account raw string cannot be blank");
        }
        String[] parts = raw.split(":");
        if (parts.length == 3) {
            return new AccountId(parts[2], parts[1], parts[0]);
        } else if (parts.length == 2) {
            return new AccountId(parts[1], null, parts[0]);
        } else {
            return new AccountId(raw, "0001", "NEXOR");
        }
    }

    @Override
    public String toString() {
        return bankCode + ":" + (branch != null ? branch + ":" : "") + number;
    }
}
