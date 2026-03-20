/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.util.ImmutableBitSet;
import org.opensearch.dsl.converter.CollationResolver;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.aggregations.BucketOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable metadata collected from walking the aggregation tree.
 *
 * Contains the resolved GROUP BY bit set, aggregate calls, bucket orders,
 * and field name lists needed by downstream converters to build
 * {@code LogicalAggregate} and {@code LogicalSort} nodes.
 *
 * Does NOT compute collations — that is the responsibility of
 * {@link CollationResolver}, which has access
 * to the actual post-aggregation schema.
 *
 * Constructed exclusively by {@link AggregationMetadataBuilder#build}.
 */
public final class AggregationMetadata {

    private final ImmutableBitSet groupByBitSet;
    private final List<String> groupByFieldNames;
    private final List<String> aggregateFieldNames;
    private final List<AggregateCall> aggregateCalls;
    private final List<BucketOrder> bucketOrders;
    private final List<QueryBuilder> bucketFilters;
    private final int filtersAggIndex;

    AggregationMetadata(
        ImmutableBitSet groupByBitSet,
        List<String> groupByFieldNames,
        List<String> aggregateFieldNames,
        List<AggregateCall> aggregateCalls,
        List<BucketOrder> bucketOrders,
        List<QueryBuilder> bucketFilters,
        int filtersAggIndex
    ) {
        this.groupByBitSet = groupByBitSet;
        this.groupByFieldNames = groupByFieldNames;
        this.aggregateFieldNames = aggregateFieldNames;
        this.aggregateCalls = aggregateCalls;
        this.bucketOrders = bucketOrders;
        this.bucketFilters = bucketFilters;
        this.filtersAggIndex = filtersAggIndex;
    }

    /** Returns the bit set of GROUP BY column indices. */
    public ImmutableBitSet getGroupByBitSet() {
        return groupByBitSet;
    }

    /** Returns the field names used in GROUP BY. */
    public List<String> getGroupByFieldNames() {
        return groupByFieldNames;
    }

    /** Returns the output field names for aggregate calls. */
    public List<String> getAggregateFieldNames() {
        return aggregateFieldNames;
    }

    /** Returns the list of Calcite aggregate calls. */
    public List<AggregateCall> getAggregateCalls() {
        return aggregateCalls;
    }

    /** Returns the bucket orders for post-aggregation sorting. */
    public List<BucketOrder> getBucketOrders() {
        return bucketOrders;
    }

    public List<QueryBuilder> getBucketFilters() {
        return bucketFilters;
    }

    /**
     * Returns the filters aggregation bucket index, or -1 if this metadata
     * is not part of a filters aggregation expansion.
     *
     * <p>When a {@code filters} aggregation is expanded by the tree walker,
     * each filter produces a separate AggregationMetadata with a unique index
     * (0, 1, 2, ...). The response builder uses this to reassemble the
     * results into the correct bucket order.
     */
    public int getFiltersAggIndex() {
        return filtersAggIndex;
    }

    /** Returns true if bucket orders are present. */
    public boolean hasBucketOrders() {
        return !bucketOrders.isEmpty();
    }
}
