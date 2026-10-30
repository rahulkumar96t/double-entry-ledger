package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.TrialBalanceRow;
import java.util.List;

public record TrialBalanceResponse(boolean balanced, List<TrialBalanceRow> currencies) {

    public static TrialBalanceResponse from(List<TrialBalanceRow> rows) {
        return new TrialBalanceResponse(rows.stream().allMatch(TrialBalanceRow::balanced), rows);
    }
}
