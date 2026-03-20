/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.opensearch.dsl.aggregation.EmptyGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.InternalFilter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Bucket shape for {@link FilterAggregationBuilder} — single-bucket filter aggregation.
 *
 * <p>Unlike terms/multi_terms which are multi-bucket (one bucket per unique key value),
 * the filter aggregation produces exactly one bucket containing all documents that
 * match its filter condition. It contributes no GROUP BY columns.
 *
 * <p>DSL example:
 * <pre>{@code
 * {
 *   "aggs": {
 *     "expensive_items": {
 *       "filter": { "range": { "price": { "gte": 1000 } } },
 *       "aggs": {
 *         "avg_price": { "avg": { "field": "price" } }
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>The filter condition is stored via {@link #getFilter} for the pipeline to apply
 * as an additional WHERE clause when building the Calcite plan for sub-aggregations.
 *
 * <p>Response: {@link InternalFilter} with a single doc_count and nested sub-agg results.
 */
public class FilterBucketShape implements BucketShape<FilterAggregationBuilder> {

    /** Creates a new filter bucket shape. */
    public FilterBucketShape() {}

    @Override
    public Class<FilterAggregationBuilder> getAggregationType() {
        return FilterAggregationBuilder.class;
    }

    /**
     * Returns an empty grouping — filter is a single-bucket aggregation
     * that does not contribute GROUP BY columns.
     *
     * <p>The filter condition itself is not a grouping; it narrows the dataset
     * for sub-aggregations rather than partitioning it into multiple buckets.
     */
    @Override
    public GroupingInfo getGrouping(FilterAggregationBuilder agg) {
        return EmptyGrouping.INSTANCE;
    }

    /**
     * Returns null — single-bucket aggregations have no bucket ordering.
     * There is only one bucket, so sorting is meaningless.
     */
    @Override
    public BucketOrder getOrder(FilterAggregationBuilder agg) {
        return null;
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(FilterAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    /**
     * Returns the filter's QueryBuilder for use by the pipeline when building
     * the Calcite plan. The filter condition should be applied as an additional
     * WHERE clause on the sub-aggregation's scan.
     *
     * @param agg the filter aggregation builder
     * @return the filter's query builder
     */
    public org.opensearch.index.query.QueryBuilder getFilter(FilterAggregationBuilder agg) {
        return agg.getFilter();
    }

    /**
     * Constructs an {@link InternalFilter} from the bucket entries.
     *
     * <p>Since filter is a single-bucket aggregation, the list will contain
     * at most one entry. If empty (no matching documents), returns a filter
     * with doc_count=0 and empty sub-aggregations.
     *
     * @param agg     the original filter aggregation builder
     * @param buckets the bucket entries (0 or 1 entries)
     * @return the constructed InternalFilter
     */
    @Override
    public InternalAggregation toBucketAggregation(FilterAggregationBuilder agg, List<BucketEntry> buckets) {
        if (buckets.isEmpty()) {
            return new InternalFilter(agg.getName(), 0, InternalAggregations.EMPTY, Map.of());
        }

        // Single-bucket: take the first (and only) entry
        BucketEntry entry = buckets.get(0);
        return new InternalFilter(
            agg.getName(),
            entry.docCount(),
            entry.subAggs(),
            Map.of()
        );
    }
}
