package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TicketDocumentServiceTest {

    @Test
    void ticketPdfContainsStableTicketSnapshot() throws Exception {
        byte[] pdf = new TicketDocumentService()
                .ticketPdf(BookingCreationWorkerTest.ticket("ABC123"));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(
                    "ABC123", "016-1234567890", "BLR", "DEL", "Maya Singh",
                    "Super Saver", "8A", "INR 2,200.00", "INR 1,246.00",
                    "INR 3,446.00", "15 kg", "•••• 4242",
                    "YOUR JOURNEY", "FARE AND PAYMENT", "UnitedAir passenger document");
        }
    }

    @Test
    void paymentReceiptPdfContainsMaskedPaymentAndNoSensitiveFields() throws Exception {
        byte[] pdf = new TicketDocumentService()
                .paymentReceiptPdf(BookingCreationWorkerTest.ticket("ABC123"));

        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Payment receipt", "ABC123", "SIM-123",
                    "•••• 4242", "INR 3,446.00");
            assertThat(text).doesNotContain("CVV", "4242424242424242");
        }
    }

    @Test
    void cancellationReceiptUsesTheUnitedAirDocumentDesignAndRefundSummary() throws Exception {
        var cancellation = new CommerceDtos.CancellationView(
                "ABC123", "UA101", "BLR", "DEL", LocalDate.of(2026, 7, 30),
                "REFUND_PENDING", new BigDecimal("3446"), new BigDecimal("2000"),
                new BigDecimal("1446"), "5 to 7 working days",
                Instant.parse("2026-07-27T10:00:00Z"));

        byte[] pdf = new CancellationDocumentService().receiptPdf(cancellation);

        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(
                    "UnitedAir AI", "CANCELLATION CONFIRMATION", "ABC123",
                    "UA101", "BLR", "DEL", "REFUND SUMMARY",
                    "INR 1,446.00", "UnitedAir passenger document");
        }
    }
}
