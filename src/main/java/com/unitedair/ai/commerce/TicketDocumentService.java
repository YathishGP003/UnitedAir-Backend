package com.unitedair.ai.commerce;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Color;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.unitedair.ai.shared.ApiExceptions;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

@Service
public class TicketDocumentService {

    private static final PDType1Font REGULAR =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);

    public byte[] ticketPdf(CommerceDtos.TicketView ticket) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("UnitedAir AI", 18, true));
        lines.add(new Line("ELECTRONIC TICKET", 10, true));
        lines.add(new Line("Booking reference (PNR): " + ticket.pnr(), 11, false));
        lines.add(new Line("Ticket number: " + value(ticket.ticketNumber()), 11, false));
        lines.add(new Line("Status: " + ticket.status(), 11, false));
        lines.add(new Line("", 6, false));
        lines.add(new Line("TRAVELLER", 11, true));
        lines.add(new Line(ticket.travellerName() + " | Date of birth: "
                + date(ticket.dateOfBirth()) + " | " + value(ticket.nationality()), 11, false));
        lines.add(new Line("Contact: " + value(ticket.contactEmail()) + " | "
                + value(ticket.contactPhone()), 11, false));
        lines.add(new Line("", 6, false));
        lines.add(new Line("YOUR JOURNEY", 11, true));
        lines.add(new Line(ticket.flightNo() + "  " + ticket.origin() + " - "
                + ticket.destination() + "  " + DATE.format(ticket.flightDate()), 12, true));
        lines.add(new Line("Departure " + ticket.departureTime() + " | Arrival "
                + ticket.arrivalTime() + " | Terminal " + ticket.terminal()
                + " | Gate " + ticket.gate(), 11, false));
        lines.add(new Line("Cabin " + ticket.cabin() + " | Fare "
                + ticket.fareClass() + " " + ticket.fareBrand()
                + " | Seat " + ticket.seatNumber(), 11, false));
        lines.add(new Line("Baggage: " + ticket.checkedBaggageKg()
                + " kg checked + " + ticket.cabinBaggageKg() + " kg cabin", 11, false));
        lines.add(new Line("", 6, false));
        lines.add(new Line("FARE AND PAYMENT", 11, true));
        lines.add(new Line("Base fare: " + money(ticket.baseFare()), 11, false));
        lines.add(new Line("Taxes and fees: " + money(ticket.taxes()), 11, false));
        lines.add(new Line("Seat fee: " + money(ticket.seatFee()), 11, false));
        lines.add(new Line("Total paid: " + money(ticket.totalPaid()), 12, true));
        lines.add(new Line("Payment: " + ticket.paymentMethod() + " "
                + ticket.maskedPayment() + " | " + ticket.paymentReference(), 11, false));
        lines.add(new Line("", 6, false));
        lines.add(new Line("Keep this document for your records. Present valid "
                + "identification at the airport.", 9, false));
        lines.add(new Line("UnitedAir passenger document | Generated securely by UnitedAir AI",
                8, false));
        return render(lines);
    }

    public byte[] paymentReceiptPdf(CommerceDtos.TicketView ticket) {
        return render(List.of(
                new Line("UnitedAir AI", 18, true),
                new Line("Payment receipt", 15, true),
                new Line("Booking reference: " + ticket.pnr(), 11, false),
                new Line("Ticket number: " + value(ticket.ticketNumber()), 11, false),
                new Line("Route: " + ticket.origin() + " - " + ticket.destination()
                        + " | " + ticket.flightNo(), 11, false),
                new Line("Traveller: " + ticket.travellerName(), 11, false),
                new Line("", 6, false),
                new Line("Base fare: " + money(ticket.baseFare()), 11, false),
                new Line("Taxes and fees: " + money(ticket.taxes()), 11, false),
                new Line("Seat fee: " + money(ticket.seatFee()), 11, false),
                new Line("Amount paid: " + money(ticket.totalPaid()), 12, true),
                new Line("Payment method: " + ticket.paymentMethod(), 11, false),
                new Line("Account: " + ticket.maskedPayment(), 11, false),
                new Line("Simulator reference: " + ticket.paymentReference(), 11, false),
                new Line("Issued: " + ticket.issuedAt().atZone(ZoneId.systemDefault()), 9, false)));
    }

    private static byte[] render(List<Line> lines) {
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
                content.setStrokingColor(new Color(0, 113, 188));
                content.setLineWidth(1.2f);
                content.moveTo(42, 729);
                content.lineTo(PDRectangle.A4.getWidth() - 42, 729);
                content.stroke();
                float y = 790;
                for (Line line : lines) {
                    content.beginText();
                    content.setNonStrokingColor(new Color(30, 43, 54));
                    content.setFont(line.bold() ? BOLD : REGULAR, line.size());
                    content.newLineAtOffset(54, y);
                    content.showText(line.text() == null ? "" : line.text());
                    content.endText();
                    y -= Math.max(18, line.size() + 6);
                }
                content.setNonStrokingColor(new Color(0, 113, 188));
                content.addRect(42, 38, PDRectangle.A4.getWidth() - 84, 2);
                content.fill();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new ApiExceptions.ApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "The document could not be generated.", e);
        }
    }

    private static String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "INR " + format.format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "Not recorded" : text;
    }

    private static String date(java.time.LocalDate date) {
        return date == null ? "Not recorded" : DATE.format(date);
    }

    private record Line(String text, int size, boolean bold) { }
}
