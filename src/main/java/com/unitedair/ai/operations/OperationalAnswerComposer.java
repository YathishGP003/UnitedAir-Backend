package com.unitedair.ai.operations;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.unitedair.ai.operations.OperationalQueryDtos.*;

/** Deterministically renders verified operational rows without changing their state. */
@Component
public class OperationalAnswerComposer {

    public String compose(List<OperationalDataResult> results) {
        if (results == null || results.isEmpty()) {
            return "No operational results were returned.";
        }
        ArrayList<String> sections = new ArrayList<>();
        int handle = 0;
        for (OperationalDataResult result : results) {
            sections.add(render(result, "[T" + (++handle) + "]"));
        }
        return String.join("\n\n", sections);
    }

    private String render(OperationalDataResult result, String handle) {
        String title = title(result.dataset());
        if (result.rows().isEmpty()) {
            return "**" + title + "**\n\nNo matching records were found "
                    + handle + ".";
        }
        StringBuilder answer = new StringBuilder("**")
                .append(title).append("**\n\n");
        for (Map<String, Object> row : result.rows()) {
            if (result.dataset() == Dataset.REFUND_CASES) {
                answer.append(renderRefund(row, handle));
            } else {
                answer.append("- ");
                boolean first = true;
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (!first) {
                        answer.append("; ");
                    }
                    answer.append(label(entry.getKey())).append(": ")
                            .append(display(entry.getValue()));
                    first = false;
                }
                answer.append(" ").append(handle).append(".\n");
            }
        }
        if (result.truncated()) {
            answer.append("\nShowing the first ").append(result.rowCount())
                    .append(" authorized records ").append(handle).append(".");
        }
        return answer.toString().trim();
    }

    private String renderRefund(Map<String, Object> row, String handle) {
        String pnr = display(first(row, "pnr", "caseReference"));
        String status = display(row.get("status"));
        Object amount = first(row, "refundAmountInr", "amount");
        StringBuilder text = new StringBuilder("- Refund ")
                .append(pnr).append(" is ")
                .append(status.toLowerCase(Locale.ROOT).replace('_', ' '));
        if (amount != null) {
            text.append("; amount INR ").append(formatNumber(amount));
        }
        if ("COMPLETED".equalsIgnoreCase(status) && row.get("completedAt") != null) {
            text.append("; completed at ").append(display(row.get("completedAt")));
        } else if (row.get("dueAt") != null) {
            text.append("; due at ").append(display(row.get("dueAt")));
        }
        return text.append(" ").append(handle).append(".\n").toString();
    }

    private static Object first(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            if (row.get(field) != null) {
                return row.get(field);
            }
        }
        return "record";
    }

    private static String title(Dataset dataset) {
        return switch (dataset) {
            case AIRPORTS -> "Airports";
            case ROUTES -> "Routes";
            case FLIGHT_SCHEDULES -> "Flight schedules";
            case FLIGHT_INSTANCES -> "Flight status";
            case FLIGHT_INVENTORY -> "Flight inventory";
            case SEAT_INVENTORY -> "Seat inventory";
            case BOOKINGS -> "Bookings";
            case CHECK_IN_STATE -> "Check-in state";
            case MEAL_AVAILABILITY -> "Meal availability";
            case SPECIAL_SERVICES -> "Special services";
            case PAYMENT_STATUS -> "Payment status";
            case REFUND_CASES -> "Refund cases";
            case REFUND_HISTORY -> "Refund history";
            case ESCALATIONS -> "Escalations";
            case OPERATIONAL_DECISIONS -> "Operational decisions";
            case AUDIT_EVENTS -> "Audit events";
        };
    }

    private static String label(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT);
    }

    private static String display(Object value) {
        return value == null ? "not recorded" : value.toString();
    }

    private static String formatNumber(Object value) {
        try {
            BigDecimal number = new BigDecimal(value.toString());
            return NumberFormat.getIntegerInstance(Locale.US).format(number);
        } catch (RuntimeException invalid) {
            return display(value);
        }
    }
}
