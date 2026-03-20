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
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregator.KeyedFilter;
import org.opensearch.search.aggregations.bucket.filter.InternalFilters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Bucket shape for {@link FiltersAggregationBuilder} — multi-bucket filters aggregation.
 *
 * <p>Each filter in the {@code filters} list produces its own bucket. Unlike terms
 * which partitions by field values, filters partitions by explicit filter conditions.
 * Each filter bucket is a single-bucket aggregation (like {@link FilterBucketShape})
 * that contributes no GROUP BY columns but applies its own WHERE clause.
 *
 * <p>The tree walker expands this into N separate granularities (one per filter),
 * each with its own bucket filter condition. The response builder reassembles
 * the N results into a single {@link InternalFilters} with N buckets.
 *
 * <p>DSL example:
 * <pre>{@code
 * {
 *   "aggs": {
 *     "status_filters": {
 *       "filters": {
 *         "filters": [
 *           { "term": { "status": "200" } },
 *           { "term": { "status": "404" } }
 *         ]
 *       },
 *       "aggs": {
 *         "avg_bytes": { "avg": { "field": "bytes" } }
 *       }
 *     }
 *   }
 * }
 * }</pre>
 */
public class FiltersBucketShape implements BucketShape<FiltersAggregationBuilder> {

    /** Creates a new filters bucket shape. */
    public FiltersBucketShape() {}

    @Override
    public Class<FiltersAggregationBuilder> getAggregationType() {
        return FiltersAggregationBuilder.class;
    }

    /**
     * Returns an empty grouping — each individual filter bucket is a single-bucket
     * aggregation that does not contribute GROUP BY columns.
     */
    @Override
    public GroupingInfo getGrouping(FiltersAggregationBuilder agg) {
        return EmptyGrouping.INSTANCE;
    }

    /** Returns null — filters aggregation has no bucket ordering. */
    @Override
    public BucketOrder getOrder(FiltersAggregationBuilder agg) {
        return null;
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(FiltersAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    /**
     * Constructs an {@link InternalFilters} from the bucket entries.
     *
     * <p>Each bucket entry corresponds to one filter. The entry's keys list
     * contains the filter key (String) at index 0. Entries are ordered by
     * filter index.
     *
     * @param agg     the original filters aggregation builder
     * @param buckets the bucket entries (one per filter, plus optional other_bucket)
     * @return the constructed InternalFilters
     */
    @Override
    public InternalAggregation toBucketAggregation(FiltersAggregationBuilder agg, List<BucketEntry> buckets) {
        List<KeyedFilter> filters = agg.filters();
        boolean keyed = agg.isKeyed();

        List<InternalFilters.InternalBucket> internalBuckets = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            BucketEntry entry = buckets.get(i);
            String key;
            if (i < filters.size()) {
                key = keyed ? filters.get(i).key() : String.valueOf(i);
            } else {
                // other_bucket
                key = agg.otherBucketKey();
            }
            internalBuckets.add(new InternalFilters.InternalBucket(
                key, entry.docCount(), entry.subAggs(), keyed
            ));
        }

        return new InternalFilters(agg.getName(), internalBuckets, keyed, Map.of());
    }
}
