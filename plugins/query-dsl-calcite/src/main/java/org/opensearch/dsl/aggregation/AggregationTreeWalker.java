/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.type.RelDataType;
import org.opensearch.dsl.aggregation.bucket.BucketShape;
import org.opensearch.dsl.aggregation.metric.MetricTranslator;
import org.opensearch.dsl.exception.ConversionException;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregator.KeyedFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recursive tree walker that detects distinct granularity levels in the
 * aggregation tree and produces one {@link AggregationMetadata} per level.
 *
 * A "granularity" is a unique GROUP BY key set determined by the accumulated
 * bucket nesting path. Metrics at different nesting depths produce separate
 * metadata instances, each yielding its own {@code LogicalAggregate}.
 */
public class AggregationTreeWalker {

    private final AggregationRegistry registry;

    /**
     * Creates a tree walker with the given type registry.
     *
     * @param registry the registry of aggregation types
     */
    public AggregationTreeWalker(AggregationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Walks the aggregation tree and produces one AggregationMetadata per
     * distinct granularity level that contains metrics.
     *
     * @param aggs The top-level aggregation builders
     * @param ctx The aggregation conversion context
     * @param inputRowType The schema before aggregation
     * @return A list of AggregationMetadata, one per granularity
     * @throws ConversionException if any aggregation fails to convert
     */
    public List<AggregationMetadata> walk(Collection<AggregationBuilder> aggs, AggregationConversionContext ctx,
            RelDataType inputRowType) throws ConversionException {
        Map<String, AggregationMetadataBuilder> granularities = new LinkedHashMap<>();
        walkRecursive(aggs, new ArrayList<>(), new ArrayList<>(), granularities, ctx);

        List<AggregationMetadata> result = new ArrayList<>();
        for (AggregationMetadataBuilder builder : granularities.values()) {
            result.add(builder.build(inputRowType, ctx));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void walkRecursive(
            Collection<AggregationBuilder> aggs,
            List<GroupingInfo> currentGroupings,
            List<QueryBuilder> currentFilters,
            Map<String, AggregationMetadataBuilder> granularities,
            AggregationConversionContext ctx) throws ConversionException {
        if (aggs == null || aggs.isEmpty()) {
            return;
        }

        for (AggregationBuilder agg : aggs) {
            AggregationType<AggregationBuilder> type = registry.findHandler(agg);

            if (type instanceof BucketShape) {
                handleBucket((BucketShape<AggregationBuilder>) type,
                    agg, currentGroupings, currentFilters, granularities, ctx);
            } else if (type instanceof MetricTranslator) {
                handleMetric((MetricTranslator<AggregationBuilder>) type,
                    agg, currentGroupings, currentFilters, granularities, ctx);
            }
        }
    }

    private void handleBucket(
            BucketShape<AggregationBuilder> shape,
            AggregationBuilder agg,
            List<GroupingInfo> currentGroupings,
            List<QueryBuilder> currentFilters,
            Map<String, AggregationMetadataBuilder> granularities,
            AggregationConversionContext ctx) throws ConversionException {

        // Filters aggregation: expand into N separate walks, one per filter.
        // Each filter gets its own granularity with a unique key suffix.
        if (agg instanceof FiltersAggregationBuilder filtersAgg) {
            handleFiltersExpansion(shape, filtersAgg, currentGroupings, currentFilters, granularities, ctx);
            return;
        }

        List<GroupingInfo> accumulatedGroupings = new ArrayList<>(currentGroupings);
        accumulatedGroupings.add(shape.getGrouping(agg));

        // Accumulate bucket-level filters from filter aggregations.
        List<QueryBuilder> accumulatedFilters = new ArrayList<>(currentFilters);
        if (agg instanceof FilterAggregationBuilder filterAgg) {
            QueryBuilder bucketFilter = filterAgg.getFilter();
            if (bucketFilter != null) {
                accumulatedFilters.add(bucketFilter);
            }
        }

        // Only create a granularity builder eagerly for multi-bucket aggregations
        // (those that contribute GROUP BY columns). Single-bucket aggregations
        // (filter, global, etc.) with EmptyGrouping don't need their own
        // granularity — they act as pass-through wrappers whose filters are
        // propagated to sub-aggregations via accumulatedFilters.
        if (hasActualGroupingFields(accumulatedGroupings)) {
            List<BucketOrder> ownOrders = new ArrayList<>();
            BucketOrder order = shape.getOrder(agg);
            if (order != null) {
                ownOrders.add(order);
            }
            getOrCreateBuilder(accumulatedGroupings, ownOrders, accumulatedFilters, granularities);
        }

        walkRecursive(shape.getSubAggregations(agg), accumulatedGroupings, accumulatedFilters, granularities, ctx);
    }

    /**
     * Expands a {@code filters} aggregation into N separate walks, one per filter.
     * Each filter produces its own set of granularities with the filter condition
     * added to {@code accumulatedFilters} and a unique granularity key suffix.
     *
     * <p>The granularity key is suffixed with {@code __filters_<index>} to ensure
     * uniqueness across filter buckets that would otherwise share the same key.
     */
    private void handleFiltersExpansion(
            BucketShape<AggregationBuilder> shape,
            FiltersAggregationBuilder filtersAgg,
            List<GroupingInfo> currentGroupings,
            List<QueryBuilder> currentFilters,
            Map<String, AggregationMetadataBuilder> granularities,
            AggregationConversionContext ctx) throws ConversionException {

        List<KeyedFilter> filters = filtersAgg.filters();
        Collection<AggregationBuilder> subAggs = shape.getSubAggregations(filtersAgg);

        for (int i = 0; i < filters.size(); i++) {
            KeyedFilter kf = filters.get(i);
            List<GroupingInfo> accGroupings = new ArrayList<>(currentGroupings);
            // Add a FiltersIndexGrouping marker so the granularity key is unique per filter
            accGroupings.add(new FiltersIndexGrouping(filtersAgg.getName(), i));

            List<QueryBuilder> accFilters = new ArrayList<>(currentFilters);
            accFilters.add(kf.filter());

            // Walk sub-aggregations with this filter's context
            walkRecursive(subAggs, accGroupings, accFilters, granularities, ctx);
        }
    }

    private void handleMetric(
            MetricTranslator<AggregationBuilder> translator,
            AggregationBuilder agg,
            List<GroupingInfo> currentGroupings,
            List<QueryBuilder> currentFilters,
            Map<String, AggregationMetadataBuilder> granularities,
            AggregationConversionContext ctx) throws ConversionException {

        // Metrics at the top level (no bucket parent) have no groupings.
        // The builder is created with empty orders since there's no bucket to order by.
        AggregationMetadataBuilder builder = getOrCreateBuilder(
            currentGroupings, List.of(), currentFilters, granularities);
        builder.addAggregateCall(translator.toAggregateCall(agg, ctx));
        builder.addAggregateFieldName(translator.getAggregateFieldName(agg));
    }

    private AggregationMetadataBuilder getOrCreateBuilder(
            List<GroupingInfo> groupings,
            List<BucketOrder> orders,
            List<QueryBuilder> filters,
            Map<String, AggregationMetadataBuilder> granularities) {
        String key = granularityKey(groupings);
        return granularities.computeIfAbsent(key, k -> {
            AggregationMetadataBuilder builder = new AggregationMetadataBuilder();
            for (GroupingInfo g : groupings) {
                builder.addGrouping(g);
                // Propagate filters agg index from FiltersIndexGrouping markers
                if (g instanceof FiltersIndexGrouping fig) {
                    builder.setFiltersAggIndex(fig.getFilterIndex());
                }
            }
            if (hasActualGroupingFields(groupings)) {
                builder.requestImplicitCount();
            }
            for (BucketOrder o : orders) {
                builder.addBucketOrder(o);
            }
            for (QueryBuilder f : filters) {
                builder.addBucketFilter(f);
            }
            return builder;
        });
    }

    /**
     * Returns true if the accumulated groupings contain at least one real
     * GROUP BY field name (i.e., not all EmptyGrouping or FiltersIndexGrouping markers).
     */
    private static boolean hasActualGroupingFields(List<GroupingInfo> groupings) {
        return groupings.stream().anyMatch(g ->
            !(g instanceof EmptyGrouping) && !(g instanceof FiltersIndexGrouping) && !g.getFieldNames().isEmpty()
        );
    }

    private static String granularityKey(List<GroupingInfo> groupings) {
        if (groupings.isEmpty()) {
            return "";
        }
        return groupings.stream()
            .flatMap(g -> g.getFieldNames().stream())
            .collect(Collectors.joining(","));
    }
}
