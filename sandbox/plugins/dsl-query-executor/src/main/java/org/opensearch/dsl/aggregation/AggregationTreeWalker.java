/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.opensearch.dsl.aggregation.bucket.BucketTranslator;
import org.opensearch.dsl.aggregation.bucket.FilterBucketTranslator;
import org.opensearch.dsl.aggregation.bucket.FiltersBucketTranslator;
import org.opensearch.dsl.aggregation.metric.MetricTranslator;
import org.opensearch.dsl.converter.ConversionContext;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recursively walks the DSL aggregation tree and produces one {@link AggregationMetadata}
 * per distinct granularity level.
 *
 * <p>A "granularity" is a unique GROUP BY key set determined by the accumulated bucket
 * nesting path. Metrics at different nesting depths produce separate metadata instances,
 * each yielding its own {@code LogicalAggregate} and {@code QueryPlan}.
 *
 * <p>Filter buckets are special: they do not add GROUP BY columns but instead carry a
 * filter condition that produces a separate plan with a {@code LogicalFilter} node.
 * Each filter bucket gets a unique granularity key using a {@code __filter__} prefix
 * to avoid collisions with field-name-based keys.
 */
public class AggregationTreeWalker {

    private final AggregationRegistry registry;

    /**
     * Creates a tree walker.
     *
     * @param registry the aggregation registry for looking up translators
     */
    public AggregationTreeWalker(AggregationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Walks the aggregation tree and returns one AggregationMetadata per granularity level.
     *
     * @param aggs the top-level aggregation builders
     * @param ctx the conversion context providing row type, type factory, and RexBuilder
     * @return metadata list, one per granularity (only levels with metrics or implicit count)
     * @throws ConversionException if any aggregation fails to convert
     */
    public List<AggregationMetadata> walk(
        Collection<AggregationBuilder> aggs,
        ConversionContext ctx
    ) throws ConversionException {
        RelDataType rowType = ctx.getRowType();
        RelDataTypeFactory typeFactory = ctx.getCluster().getTypeFactory();
        Map<String, AggregationMetadataBuilder> granularities = new LinkedHashMap<>();
        walkRecursive(aggs, new ArrayList<>(), null, null, granularities, ctx);

        List<AggregationMetadata> result = new ArrayList<>();
        for (AggregationMetadataBuilder builder : granularities.values()) {
            if (builder.hasAggregateCalls()) {
                result.add(builder.build(rowType, typeFactory));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void walkRecursive(
        Collection<AggregationBuilder> aggs,
        List<GroupingInfo> currentGroupings,
        RexNode currentFilterCondition,
        String currentFilterKey,
        Map<String, AggregationMetadataBuilder> granularities,
        ConversionContext ctx
    ) throws ConversionException {
        for (AggregationBuilder aggBuilder : aggs) {
            AggregationType<?> type = registry.get(aggBuilder.getClass());

            if (type instanceof FiltersBucketTranslator) {
                handleFiltersBucket(
                    (FiltersBucketTranslator) type, (FiltersAggregationBuilder) aggBuilder, currentGroupings,
                    currentFilterCondition, currentFilterKey, granularities, ctx
                );
            } else if (type instanceof BucketTranslator) {
                handleBucket(
                    (BucketTranslator<AggregationBuilder>) type, aggBuilder, currentGroupings,
                    currentFilterCondition, currentFilterKey, granularities, ctx
                );
            } else if (type instanceof MetricTranslator) {
                handleMetric(
                    (MetricTranslator<AggregationBuilder>) type, aggBuilder, currentGroupings,
                    currentFilterKey, granularities, ctx
                );
            } else {
                throw new ConversionException("Unsupported aggregation type: " + aggBuilder.getClass().getSimpleName());
            }
        }
    }

    private void handleBucket(
        BucketTranslator<AggregationBuilder> translator,
        AggregationBuilder aggBuilder,
        List<GroupingInfo> currentGroupings,
        RexNode currentFilterCondition,
        String currentFilterKey,
        Map<String, AggregationMetadataBuilder> granularities,
        ConversionContext ctx
    ) throws ConversionException {
        GroupingInfo grouping = translator.getGrouping(aggBuilder);
        RexNode combinedFilter = currentFilterCondition;
        String filterKey = currentFilterKey;

        // For filter buckets: extract filter condition and compute unique key
        if (aggBuilder instanceof FilterAggregationBuilder filterAgg) {
            FilterBucketTranslator filterTranslator = (FilterBucketTranslator) (BucketTranslator<?>) translator;
            RexNode filterCondition = filterTranslator.getFilterCondition(filterAgg, ctx);

            combinedFilter = currentFilterCondition != null
                ? ctx.getRexBuilder().makeCall(SqlStdOperatorTable.AND, currentFilterCondition, filterCondition)
                : filterCondition;

            filterKey = filterGranularityKey(currentGroupings, aggBuilder.getName());
        }

        // Accumulate groupings (empty for filter buckets, non-empty for terms etc.)
        List<GroupingInfo> accumulatedGroupings = new ArrayList<>(currentGroupings);
        accumulatedGroupings.add(grouping);

        // Get or create builder — for filter buckets, use the filter key
        if (aggBuilder instanceof FilterAggregationBuilder) {
            AggregationMetadataBuilder builder = getOrCreateBuilder(filterKey, accumulatedGroupings, granularities);
            builder.setFilterCondition(combinedFilter);
        } else {
            getOrCreateBuilder(accumulatedGroupings, granularities);
        }

        // Recurse into sub-aggregations
        Collection<AggregationBuilder> subAggs = translator.getSubAggregations(aggBuilder);
        if (subAggs != null && !subAggs.isEmpty()) {
            walkRecursive(subAggs, accumulatedGroupings, combinedFilter, filterKey, granularities, ctx);
        }
    }

    private void handleFiltersBucket(
        FiltersBucketTranslator translator,
        FiltersAggregationBuilder aggBuilder,
        List<GroupingInfo> currentGroupings,
        RexNode currentFilterCondition,
        String currentFilterKey,
        Map<String, AggregationMetadataBuilder> granularities,
        ConversionContext ctx
    ) throws ConversionException {
        GroupingInfo grouping = translator.getGrouping(aggBuilder);
        List<FiltersAggregator.KeyedFilter> keyedFilters = translator.getKeyedFilters(aggBuilder);
        Collection<AggregationBuilder> subAggs = translator.getSubAggregations(aggBuilder);

        // Convert all filter conditions upfront (needed for other bucket too)
        List<RexNode> filterConditions = new ArrayList<>();
        for (FiltersAggregator.KeyedFilter kf : keyedFilters) {
            filterConditions.add(translator.convertFilter(kf.filter(), ctx));
        }

        // Process each keyed filter as a separate plan
        for (int i = 0; i < keyedFilters.size(); i++) {
            String filterKey = keyedFilters.get(i).key();
            RexNode filterCondition = filterConditions.get(i);

            RexNode combinedFilter = currentFilterCondition != null
                ? ctx.getRexBuilder().makeCall(SqlStdOperatorTable.AND, currentFilterCondition, filterCondition)
                : filterCondition;

            String granKey = filtersGranularityKey(currentGroupings, aggBuilder.getName(), filterKey);

            List<GroupingInfo> accumulatedGroupings = new ArrayList<>(currentGroupings);
            accumulatedGroupings.add(grouping);

            AggregationMetadataBuilder builder = getOrCreateBuilder(granKey, accumulatedGroupings, granularities);
            builder.setFilterCondition(combinedFilter);
            builder.setBucketKey(filterKey);
            builder.setAggregationName(aggBuilder.getName());

            if (subAggs != null && subAggs.isEmpty() == false) {
                walkRecursive(subAggs, accumulatedGroupings, combinedFilter, granKey, granularities, ctx);
            }
        }

        // Other bucket: NOT(OR(all filters))
        if (aggBuilder.otherBucket()) {
            RexNode otherCondition = translator.buildOtherBucketCondition(filterConditions, ctx);

            RexNode combinedOther = currentFilterCondition != null
                ? ctx.getRexBuilder().makeCall(SqlStdOperatorTable.AND, currentFilterCondition, otherCondition)
                : otherCondition;

            String otherKey = aggBuilder.otherBucketKey();
            String granKey = filtersGranularityKey(currentGroupings, aggBuilder.getName(), otherKey);

            List<GroupingInfo> accumulatedGroupings = new ArrayList<>(currentGroupings);
            accumulatedGroupings.add(grouping);

            AggregationMetadataBuilder builder = getOrCreateBuilder(granKey, accumulatedGroupings, granularities);
            builder.setFilterCondition(combinedOther);
            builder.setBucketKey(otherKey);
            builder.setAggregationName(aggBuilder.getName());

            if (subAggs != null && subAggs.isEmpty() == false) {
                walkRecursive(subAggs, accumulatedGroupings, combinedOther, granKey, granularities, ctx);
            }
        }
    }

    private void handleMetric(
        MetricTranslator<AggregationBuilder> translator,
        AggregationBuilder aggBuilder,
        List<GroupingInfo> currentGroupings,
        String currentFilterKey,
        Map<String, AggregationMetadataBuilder> granularities,
        ConversionContext ctx
    ) throws ConversionException {
        RelDataType rowType = ctx.getRowType();
        AggregationMetadataBuilder builder;
        if (currentFilterKey != null) {
            builder = granularities.get(currentFilterKey);
        } else {
            builder = getOrCreateBuilder(currentGroupings, granularities);
        }
        builder.addAggregateCall(
            translator.toAggregateCall(aggBuilder, rowType),
            translator.getAggregateFieldName(aggBuilder)
        );
    }

    private AggregationMetadataBuilder getOrCreateBuilder(
        List<GroupingInfo> groupings,
        Map<String, AggregationMetadataBuilder> granularities
    ) {
        String key = granularityKey(groupings);
        return getOrCreateBuilder(key, groupings, granularities);
    }

    private AggregationMetadataBuilder getOrCreateBuilder(
        String key,
        List<GroupingInfo> groupings,
        Map<String, AggregationMetadataBuilder> granularities
    ) {
        AggregationMetadataBuilder existing = granularities.get(key);
        if (existing != null) {
            return existing;
        }

        AggregationMetadataBuilder builder = new AggregationMetadataBuilder();
        for (GroupingInfo g : groupings) {
            builder.addGrouping(g);
        }
        if (!groupings.isEmpty()) {
            builder.requestImplicitCount();
        }
        granularities.put(key, builder);
        return builder;
    }

    private static String granularityKey(List<GroupingInfo> groupings) {
        if (groupings.isEmpty()) {
            return "";
        }
        return groupings.stream()
            .flatMap(g -> g.getFieldNames().stream())
            .collect(Collectors.joining(","));
    }

    private static String filterGranularityKey(List<GroupingInfo> groupings, String filterName) {
        String base = groupings.stream()
            .flatMap(g -> g.getFieldNames().stream())
            .collect(Collectors.joining(","));
        return base + "__filter__" + filterName;
    }

    private static String filtersGranularityKey(List<GroupingInfo> groupings, String aggName, String filterKey) {
        String base = groupings.stream()
            .flatMap(g -> g.getFieldNames().stream())
            .collect(Collectors.joining(","));
        return base + "__filter__" + aggName + "/" + filterKey;
    }
}
