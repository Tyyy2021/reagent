package com.reagent.core;

import com.reagent.profile.TaskToolCatalog;
import com.reagent.tool.ToolContext;

import java.util.List;

/** Owns tool-batch recovery classification, execution, and ordered durable results. */
public interface ToolBatchCoordinator {

    BatchDisposition process(
            TaskRunToken token,
            ToolContext toolContext,
            Context context,
            TaskToolCatalog catalog,
            List<ToolCall> calls
    );
}
