package com.unitedair.ai.commerce;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import com.unitedair.ai.shared.ApiExceptions;
import org.springframework.stereotype.Service;

@Service
public class SimulatedPaymentService {

    private final PaymentRepository repository;

    public SimulatedPaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    public CommerceDtos.PaymentView authorize(
            long userId, CommerceDtos.PaymentRequest request) {
        validateCommon(request);
        return repository.findByIdempotency(userId, request.idempotencyKey())
                .orElseGet(() -> authorizeOnce(userId, request));
    }

    private CommerceDtos.PaymentView authorizeOnce(
            long userId, CommerceDtos.PaymentRequest request) {
        String method = request.method().trim().toUpperCase(Locale.ROOT);
        String masked;
        boolean approved;
        if ("CARD".equals(method)) {
            String digits = request.cardNumber() == null
                    ? "" : request.cardNumber().replaceAll("\\D", "");
            if (digits.length() < 12 || digits.length() > 19) {
                throw new ApiExceptions.BadRequest("Enter a valid demo card number.");
            }
            if (request.cvv() == null || !request.cvv().matches("\\d{3,4}")) {
                throw new ApiExceptions.BadRequest("Enter a valid CVV.");
            }
            String lastFour = digits.substring(digits.length() - 4);
            masked = "•••• " + lastFour;
            approved = !lastFour.equals("0002") && !lastFour.equals("9995");
        } else if ("UPI".equals(method)) {
            String upi = request.upi() == null
                    ? "" : request.upi().trim().toLowerCase(Locale.ROOT);
            if (!upi.matches("[a-z0-9._-]{2,64}@[a-z0-9.-]{2,32}")) {
                throw new ApiExceptions.BadRequest("Enter a valid demo UPI ID.");
            }
            int at = upi.indexOf('@');
            masked = upi.substring(0, Math.min(2, at)) + "•••" + upi.substring(at);
            approved = !upi.startsWith("decline@");
        } else {
            throw new ApiExceptions.BadRequest("Payment method must be CARD or UPI.");
        }

        String status = approved ? "AUTHORIZED" : "DECLINED";
        String providerReference = approved
                ? "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                : null;
        return repository.create(
                userId, request.draftUuid(), method, status, masked,
                providerReference, request.amount(), request.idempotencyKey());
    }

    private static void validateCommon(CommerceDtos.PaymentRequest request) {
        if (request == null || request.draftUuid() == null) {
            throw new ApiExceptions.BadRequest("A booking draft is required.");
        }
        if (request.method() == null || request.method().isBlank()) {
            throw new ApiExceptions.BadRequest("Payment method must be CARD or UPI.");
        }
        if (request.amount() == null
                || request.amount().compareTo(BigDecimal.ZERO) <= 0
                || request.amount().scale() > 2) {
            throw new ApiExceptions.BadRequest("Enter a valid payment amount.");
        }
        if (request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 36) {
            throw new ApiExceptions.BadRequest("A valid payment idempotency key is required.");
        }
    }
}
