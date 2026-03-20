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
 * Marker grouping for {@code filters} aggregation expansion.
 *
 * <p>Like {@link EmptyGrouping}, this contributes no GROUP BY columns.
 * However, it carries a unique identifier (aggregation name + filter index)
 * that makes the granularity key unique per filter bucket.
 *
 * <p>The {@link #getFieldNames()} returns a synthetic name like
 * {@code __filters_myAggName_0} which is used only for granularity key
 * computation and is never resolved to an actual index column.
 */
public final class FiltersIndexGrouping implements GroupingInfo {

    private final String aggName;
    private final int filterIndex;

    /**
     * Creates a filters index grouping marker.
     *
     * @param aggName     the name of the filters aggregation
     * @param filterIndex the 0-based index of this filter within the filters list
     */
    public FiltersIndexGrouping(String aggName, int filterIndex) {
        this.aggName = aggName;
        this.filterIndex = filterIndex;
    }

    /** Returns the 0-based filter index. */
    public int getFilterIndex() {
        return filterIndex;
    }

    /** Returns the filters aggregation name. */
    public String getAggName() {
        return aggName;
    }

    /**
     * Returns a synthetic field name used only for granularity key uniqueness.
     * This is never resolved to an actual column index.
     */
    @Override
    public List<String> getFieldNames() {
        return List.of("__filters_" + aggName + "_" + filterIndex);
    }

    /**
     * Returns empty — this grouping contributes no GROUP BY columns.
     */
    @Override
    public List<Integer> resolveIndices(RelDataType inputRowType) throws ConversionException {
        return List.of();
    }
}
