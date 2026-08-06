package com.unitedair.ai.commerce;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import com.unitedair.ai.shared.ApiExceptions;
import com.unitedair.ai.tools.ToolDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundWorkItemService {

    private static final Map<RefundDtos.RefundStatus, Set<RefundDtos.RefundStatus>>
            TRANSITIONS = Map.of(
                    RefundDtos.RefundStatus.PENDING, Set.of(
                            RefundDtos.RefundStatus.PROCESSING,
                            RefundDtos.RefundStatus.CONTACT_NEEDED,
                            RefundDtos.RefundStatus.FAILED),
                    RefundDtos.RefundStatus.PROCESSING, Set.of(
                            RefundDtos.RefundStatus.CONTACT_NEEDED,
                            RefundDtos.RefundStatus.COMPLETED,
                            RefundDtos.RefundStatus.FAILED),
                    RefundDtos.RefundStatus.CONTACT_NEEDED, Set.of(
                            RefundDtos.RefundStatus.PROCESSING,
                            RefundDtos.RefundStatus.FAILED),
                    RefundDtos.RefundStatus.FAILED, Set.of(
                            RefundDtos.RefundStatus.PROCESSING),
                    RefundDtos.RefundStatus.COMPLETED, Set.of());

    private final RefundWorkItemRepository repository;

    public RefundWorkItemService(RefundWorkItemRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RefundDtos.RefundCaseView createForCancellation(
            long passengerUserId, String pnr, ToolDtos.RefundQuote quote) {
        String normalizedPnr = pnr == null
                ? "" : pnr.trim().toUpperCase(Locale.ROOT);
        if (!normalizedPnr.matches("[A-Z0-9]{6}")) {
            throw new ApiExceptions.BadRequest("Enter a valid six-character PNR.");
        }
        return repository.createForCancellation(passengerUserId, normalizedPnr, quote);
    }

    public List<RefundDtos.RefundCaseView> listForPassenger(long passengerUserId) {
        return repository.listForPassenger(passengerUserId);
    }

    public List<RefundDtos.RefundCaseView> listForStaff(String status) {
        RefundDtos.RefundStatus filter = status == null || status.isBlank()
                ? null : parseStatus(status);
        return repository.listForStaff(filter);
    }

    public List<RefundDtos.RefundCaseView> listForStaff(
            RefundDtos.RefundCaseQuery query) {
        RefundDtos.RefundCaseQuery safe = query == null
                ? new RefundDtos.RefundCaseQuery(null, null, 50)
                : query;
        RefundDtos.RefundStatus filter = safe.status() == null || safe.status().isBlank()
                ? null : parseStatus(safe.status());
        return repository.listForStaff(filter, safe.dueBefore(), safe.limit());
    }

    public RefundDtos.RefundCaseView statusForPassenger(
            long passengerUserId, String pnr) {
        return repository.findForPassenger(passengerUserId, normalizePnr(pnr))
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No refund case was found for that booking."));
    }

    public RefundDtos.RefundCaseView statusForStaff(String pnr) {
        return repository.findForStaffByPnr(normalizePnr(pnr))
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "No refund case was found for that booking."));
    }

    @Transactional
    public RefundDtos.RefundCaseView transition(
            UUID caseUuid,
            RefundDtos.RefundStatus target,
            String note,
            long staffUserId) {
        RefundDtos.RefundCaseView current = repository.findForStaff(caseUuid)
                .orElseThrow(() -> new ApiExceptions.NotFound("Refund case not found."));
        if (!allowedTargets(current.status()).contains(target)) {
            throw new ApiExceptions.Conflict(
                    "Refund case cannot move from " + current.status()
                            + " to " + target + ".");
        }
        String cleanNote = note == null ? "" : note.trim();
        if ((target == RefundDtos.RefundStatus.CONTACT_NEEDED
                || target == RefundDtos.RefundStatus.FAILED)
                && cleanNote.isBlank()) {
            throw new ApiExceptions.BadRequest(
                    "Add a note explaining why this refund needs attention.");
        }
        return repository.transition(
                caseUuid, current.status(), target, cleanNote, staffUserId);
    }

    public RefundDtos.RefundCaseView transition(
            UUID caseUuid, String status, String note, long staffUserId) {
        return transition(caseUuid, parseStatus(status), note, staffUserId);
    }

    static Set<RefundDtos.RefundStatus> allowedTargets(
            RefundDtos.RefundStatus current) {
        return TRANSITIONS.getOrDefault(current, Set.of());
    }

    private static RefundDtos.RefundStatus parseStatus(String value) {
        try {
            return RefundDtos.RefundStatus.parse(value);
        } catch (RuntimeException invalid) {
            throw new ApiExceptions.BadRequest("Unknown refund status.");
        }
    }

    private static String normalizePnr(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{6}")) {
            throw new ApiExceptions.BadRequest("Enter a valid six-character PNR.");
        }
        return normalized;
    }
}
