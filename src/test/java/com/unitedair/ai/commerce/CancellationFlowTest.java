package com.unitedair.ai.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class CancellationFlowTest {

    @Test
    void callbackRequestCreatesSupportCaseWithoutCancellingBooking() {
        CallbackRepository repository = mock(CallbackRepository.class);
        var expected = new CommerceDtos.CallbackView(
                UUID.randomUUID(), "ABC123", "PHONE", "PENDING", Instant.now());
        when(repository.createOwned(7L, "ABC123", "PHONE")).thenReturn(expected);

        var result = new CallbackService(repository)
                .request(7L, "abc123", "phone");

        assertThat(result.status()).isEqualTo("PENDING");
        verify(repository).createOwned(7L, "ABC123", "PHONE");
    }

    @Test
    void cancellationReceiptClearlyShowsFeeAndRefund() throws Exception {
        var cancellation = new CommerceDtos.CancellationView(
                "ABC123", "UA101", "BLR", "DEL", LocalDate.now().plusDays(3),
                "CANCELLED", new BigDecimal("3446.00"), new BigDecimal("2200.00"),
                new BigDecimal("1246.00"), "5-7 business days", Instant.now());

        byte[] pdf = new CancellationDocumentService().receiptPdf(cancellation);

        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(
                    "Cancellation receipt", "ABC123", "UA101", "BLR", "DEL",
                    "INR 3,446.00", "INR 2,200.00", "INR 1,246.00");
        }
    }
}
