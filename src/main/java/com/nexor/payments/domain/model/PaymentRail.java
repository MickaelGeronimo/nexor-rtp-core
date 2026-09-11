package com.nexor.payments.domain.model;

/**
 * Clearing and settlement rails supported by the core.
 */
public enum PaymentRail {
    /** Brazilian Central Bank Real-Time Rail (SPI / Pix) */
    PIX,

    /** US Real-Time Payments Network (The Clearing House) */
    RTP_TCH,

    /** Federal Reserve Real-Time Rail (FedNow Service) */
    FEDNOW,

    /** Intra-bank instantaneous book transfer (same financial institution, zero clearing cost) */
    BOOK_TRANSFER
}
