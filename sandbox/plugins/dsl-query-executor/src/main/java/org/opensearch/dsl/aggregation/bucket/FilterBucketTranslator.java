/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.apache.calcite.rex.RexNode;
import org.opensearch.dsl.aggregation.EmptyGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.converter.ConversionContext;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.query.QueryRegistry;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.bucket.filter.FilterAggregationBuilder;

import java.util.Collection;
import java.util.List;

/**
 * Translates a {@link FilterAggregationBuilder} — a bucket that narrows the document set
 * for nested sub-aggregations using a query clause, producing a {@code LogicalFilter}
 * in the Calcite plan rather than a {@code GROUP BY}.
 *
 * <p>Example: {@code {"aggs": {"active": {"filter": {"term": {"status": "active"}}, "aggs": {"avg_price": {"avg": {"field": "price"}}}}}}}
 * becomes {@code SELECT AVG(price) FROM table WHERE status = 'active'}.
 */
public class FilterBucketTranslator implements BucketTranslator<FilterAggregationBuilder> {

    private final QueryRegistry queryRegistry;

    /**
     * Creates a filter bucket translator.
     *
     * @param queryRegistry the registry for converting query clauses to RexNode expressions
     */
    public FilterBucketTranslator(QueryRegistry queryRegistry) {
        this.queryRegistry = queryRegistry;
    }

    @Override
    public Class<FilterAggregationBuilder> getAggregationType() {
        return FilterAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(FilterAggregationBuilder agg) {
        return new EmptyGrouping();
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(FilterAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    /**
     * Converts the filter query clause to a {@link RexNode} using the {@link QueryRegistry}.
     * Requires {@link ConversionContext} for {@code RexBuilder} and row type access.
     *
     * @param agg the filter aggregation builder
     * @param ctx the conversion context
     * @return the RexNode representing the filter condition
     * @throws ConversionException if the filter query is null or conversion fails
     */
    public RexNode getFilterCondition(FilterAggregationBuilder agg, ConversionContext ctx) throws ConversionException {
        QueryBuilder filterQuery = agg.getFilter();
        if (filterQuery == null) {
            throw new ConversionException("Filter aggregation '" + agg.getName() + "' requires a query clause");
        }
        return queryRegistry.convert(filterQuery, ctx);
    }

    @Override
    public InternalAggregation toBucketAggregation(FilterAggregationBuilder agg, List<BucketEntry> buckets) {
        throw new UnsupportedOperationException("toBucketAggregation not yet implemented");
    }
}
