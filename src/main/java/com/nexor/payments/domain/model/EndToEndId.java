package com.nexor.payments.domain.model;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Unique End-to-End Identification for instant payments, conforming to ISO 20022 and Pix / FedNow standards.
 * Example structure: E{ISPB/Routing}{YYYYMMDDhhmm}{RandomAlphanumeric}
 */
public record EndToEndId(String value) {
    public EndToEndId {
        Objects.requireNonNull(value, "EndToEndId cannot be null");
        if (value.length() < 16 || value.length() > 35) {
            throw new IllegalArgumentException("EndToEndId length must be between 16 and 35 characters, was: " + value.length());
        }
    }

    public static EndToEndId generate(String prefix) {
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long randomSuffix = ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L);
        String cleanPrefix = (prefix == null || prefix.isBlank()) ? "E" : prefix.trim();
        return new EndToEndId(cleanPrefix + timestamp + randomSuffix);
    }

    @Override
    public String toString() {
        return value;
    }
}
