package com.nexor.payments.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PaymentRequestDto(
        @NotBlank(message = "debtorAccount is mandatory")
        String debtorAccount,

        @NotBlank(message = "creditorAccount is mandatory")
        String creditorAccount,

        @NotBlank(message = "amount is mandatory")
        @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "amount must be a valid positive monetary format with up to 2 decimal places")
        String amount,

        @NotBlank(message = "currency is mandatory")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO-4217 code (e.g. BRL, USD)")
        String currency,

        String remittanceInformation,

        String rail
) {}
