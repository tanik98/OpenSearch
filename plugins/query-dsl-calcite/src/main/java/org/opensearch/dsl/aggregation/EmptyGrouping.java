/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.type.RelDataType;
import org.opensearch.dsl.exception.ConversionException;

import java.util.List;

/**
 * Empty grouping for single-bucket aggregations (filter, global, missing, etc.).
 *
 * <p>Single-bucket aggregations produce exactly one bucket and do not partition
 * data by field values. They contribute no GROUP BY columns to the Calcite plan.
 *
 * <p>This is a singleton — use {@link #INSTANCE}.
 */
public final class EmptyGrouping implements GroupingInfo {

    /** Shared singleton instance. */
    public static final EmptyGrouping INSTANCE = new EmptyGrouping();

    private EmptyGrouping() {}

    @Override
    public List<String> getFieldNames() {
        return List.of();
    }

    @Override
    public List<Integer> resolveIndices(RelDataType inputRowType) throws ConversionException {
        return List.of();
    }
}
