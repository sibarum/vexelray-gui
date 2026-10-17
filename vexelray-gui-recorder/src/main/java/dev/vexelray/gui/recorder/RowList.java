package dev.vexelray.gui.recorder;

import java.util.List;

/**
 * The answer to {@code rows}: the most recent rows that matched, oldest first.
 *
 * @param span   the stretch of the ring searched
 * @param filter what was asked for
 * @param rows   the rows kept, at most {@code filter.limit()}
 * @param more   rows that matched and were left out, all older than those kept
 */
public record RowList(Span span, RowFilter filter, List<RecordedRow> rows, long more) {

    public RowList {
        rows = List.copyOf(rows);
    }
}
