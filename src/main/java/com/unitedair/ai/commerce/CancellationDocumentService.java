package com.unitedair.ai.commerce;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Color;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

import com.unitedair.ai.shared.ApiExceptions;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CancellationDocumentService {

    public byte[] receiptPdf(CommerceDtos.CancellationView cancellation) {
        var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(new Color(0, 113, 188));
                content.addRect(0, 824, PDRectangle.A4.getWidth(), 18);
                content.fill();
                content.setNonStrokingColor(new Color(244, 248, 251));
                content.addRect(42, 742, PDRectangle.A4.getWidth() - 84, 42);
                content.fill();
                content.setNonStrokingColor(new Color(30, 43, 54));
                float y = 790;
                y = line(content, bold, 18, y, "UnitedAir AI");
                y = line(content, bold, 10, y, "CANCELLATION CONFIRMATION");
                y = line(content, regular, 9, y, "Cancellation receipt");
                y = line(content, regular, 11, y,
                        "Booking reference: " + cancellation.pnr());
                y = line(content, regular, 11, y,
                        "Flight: " + cancellation.flightNo() + "  "
                                + cancellation.origin() + " - " + cancellation.destination());
                y = line(content, regular, 11, y,
                        "Travel date: " + cancellation.flightDate());
                y = line(content, regular, 11, y,
                        "Status: " + cancellation.status());
                y -= 10;
                y = line(content, bold, 11, y, "REFUND SUMMARY");
                y = line(content, regular, 11, y,
                        "Amount paid: " + money(cancellation.amountPaid()));
                y = line(content, regular, 11, y,
                        "Cancellation fee: " + money(cancellation.cancellationFee()));
                y = line(content, bold, 12, y,
                        "Refund: " + money(cancellation.refundAmount()));
                y = line(content, regular, 10, y,
                        "Expected timeline: " + cancellation.refundTimeline());
                y -= 18;
                line(content, regular, 8, y,
                        "UnitedAir passenger document | Generated securely by UnitedAir AI");
                content.setNonStrokingColor(new Color(0, 113, 188));
                content.addRect(42, 38, PDRectangle.A4.getWidth() - 84, 2);
                content.fill();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new ApiExceptions.ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "The cancellation receipt could not be generated.", e);
        }
    }

    private static float line(
            PDPageContentStream content, PDType1Font font, int size, float y, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(54, y);
        content.showText(text);
        content.endText();
        return y - Math.max(18, size + 6);
    }

    private static String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "INR " + format.format(amount == null ? BigDecimal.ZERO : amount);
    }
}
