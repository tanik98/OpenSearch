/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.converter;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rex.RexNode;
import org.opensearch.dsl.ConversionContext;
import org.opensearch.dsl.aggregation.AggregationMetadata;
import org.opensearch.dsl.query.QueryRegistry;
import org.opensearch.dsl.exception.ConversionException;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.QueryBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the DSL query to a Calcite LogicalFilter.
 * Skips match_all since LogicalTableScan already returns all rows.
 *
 * <p>Also applies bucket-level filters from {@link AggregationMetadata#getBucketFilters()}
 * when running in the aggregation pipeline. These are filters from
 * {@code FilterAggregationBuilder} nodes that should narrow the dataset
 * for sub-aggregations.
 */
public class FilterConverter extends AbstractDslConverter {

    private final QueryRegistry queryRegistry;

    /**
     * Creates a new FilterConverter.
     *
     * @param queryRegistry the registry for resolving query translators
     */
    public FilterConverter(QueryRegistry queryRegistry) {
        this.queryRegistry = queryRegistry;
    }

    @Override
    protected boolean isApplicable(ConversionContext ctx) {
        boolean hasTopLevelQuery = ctx.getSearchSource().query() != null
            && !(ctx.getSearchSource().query() instanceof MatchAllQueryBuilder);
        boolean hasBucketFilters = hasBucketFilters(ctx);
        return hasTopLevelQuery || hasBucketFilters;
    }

    @Override
    protected void validate(ConversionContext ctx) throws ConversionException {
        ctx.requireRelNodeSupported(LogicalFilter.class);
    }

    @Override
    protected RelNode doConvert(RelNode input, ConversionContext ctx) throws ConversionException {
        List<RexNode> conditions = new ArrayList<>();

        // Top-level query filter
        QueryBuilder topQuery = ctx.getSearchSource().query();
        if (topQuery != null && !(topQuery instanceof MatchAllQueryBuilder)) {
            conditions.add(queryRegistry.convert(topQuery, ctx));
        }

        // Bucket-level filters from filter aggregations
        if (hasBucketFilters(ctx)) {
            for (QueryBuilder bucketFilter : ctx.getAggregationMetadata().getBucketFilters()) {
                conditions.add(queryRegistry.convert(bucketFilter, ctx));
            }
        }

        // AND all conditions together
        RexNode combined;
        if (conditions.size() == 1) {
            combined = conditions.get(0);
        } else {
            combined = ctx.getRexBuilder().makeCall(
                org.apache.calcite.sql.fun.SqlStdOperatorTable.AND,
                conditions
            );
        }

        return LogicalFilter.create(input, combined);
    }

    private static boolean hasBucketFilters(ConversionContext ctx) {
        AggregationMetadata metadata = ctx.getAggregationMetadata();
        return metadata != null
            && metadata.getBucketFilters() != null
            && !metadata.getBucketFilters().isEmpty();
    }
}
