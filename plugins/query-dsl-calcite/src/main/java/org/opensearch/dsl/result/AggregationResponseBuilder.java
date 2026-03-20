/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.result;

import org.opensearch.dsl.aggregation.AggregationMetadata;
import org.opensearch.dsl.aggregation.AggregationMetadataBuilder;
import org.opensearch.dsl.aggregation.AggregationRegistry;
import org.opensearch.dsl.aggregation.AggregationType;
import org.opensearch.dsl.aggregation.bucket.BucketShape;
import org.opensearch.dsl.aggregation.metric.MetricTranslator;
import org.opensearch.dsl.exception.ConversionException;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregator.KeyedFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts flat {@code Object[][]} execution results from multiple granularity levels
 * into a single nested {@link InternalAggregations} matching the original DSL aggregation tree.
 *
 * Each granularity level (distinct GROUP BY key set) produces a separate {@link ExecutionResult}.
 * This builder walks the original aggregation tree, correlates each node to the correct
 * granularity's result, and reconstructs the nested bucket structure.
 */
public final class AggregationResponseBuilder {

    private final AggregationRegistry registry;
    private final Map<String, ExecutionResult> granularityMap;
    private final Map<String, Map<Integer, ExecutionResult>> filtersGranularityMap;

    /**
     * Creates a new builder.
     *
     * @param registry   the aggregation registry for finding handlers
     * @param aggResults all AGGREGATION execution results (one per granularity)
     */
    public AggregationResponseBuilder(AggregationRegistry registry, List<ExecutionResult> aggResults) {
        this.registry = registry;
        this.granularityMap = new HashMap<>();
        this.filtersGranularityMap = new HashMap<>();
        for (ExecutionResult result : aggResults) {
            String key = granularityKey(result);
            AggregationMetadata meta = result.getAggregationMetadata();
            if (meta != null && meta.getFiltersAggIndex() >= 0) {
                // This result belongs to a filters aggregation expansion.
                // Strip the __filters_* suffix from the key to get the base key,
                // then index by filter index.
                String baseKey = stripFiltersKeySuffix(key);
                filtersGranularityMap
                    .computeIfAbsent(baseKey, k -> new HashMap<>())
                    .put(meta.getFiltersAggIndex(), result);
            } else {
                granularityMap.put(key, result);
            }
        }
    }

    /**
     * Builds the merged InternalAggregations from the original aggregation tree.
     *
     * @param originalAggs the top-level aggregation builders from SearchSourceBuilder
     * @return the nested InternalAggregations
     */
    public InternalAggregations build(Collection<AggregationBuilder> originalAggs) throws ConversionException {
        List<InternalAggregation> aggs = buildLevel(originalAggs, new ArrayList<>(), Map.of());
        return InternalAggregations.from(aggs);
    }

    /**
     * Recursively builds InternalAggregations for one level of the aggregation tree.
     *
     * @param aggs                the aggregation builders at this level
     * @param accumulatedGroupFields accumulated GROUP BY field names from parent buckets
     * @param parentKeyFilter     filter to match parent bucket keys in deeper granularity results
     * @return list of InternalAggregation for this level
     */
    @SuppressWarnings("unchecked")
    private List<InternalAggregation> buildLevel(
            Collection<AggregationBuilder> aggs,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) throws ConversionException {

        List<InternalAggregation> result = new ArrayList<>();

        for (AggregationBuilder agg : aggs) {
            AggregationType<AggregationBuilder> type = registry.findHandler(agg);

            if (type instanceof MetricTranslator) {
                result.add(buildMetric((MetricTranslator<AggregationBuilder>) type, agg,
                    accumulatedGroupFields, parentKeyFilter));
            } else if (type instanceof BucketShape) {
                result.add(buildBucket((BucketShape<AggregationBuilder>) type, agg,
                    accumulatedGroupFields, parentKeyFilter));
            }
        }
        return result;
    }

    private InternalAggregation buildMetric(
            MetricTranslator<AggregationBuilder> translator,
            AggregationBuilder agg,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) {

        String granularityKey = String.join(",", accumulatedGroupFields);
        ExecutionResult result = lookupResult(granularityKey);
        if (result == null || result.getRows().length == 0) {
            return translator.toInternalAggregation(agg.getName(), null);
        }

        Map<String, Integer> colIndex = buildColumnIndex(result);
        String metricFieldName = agg.getName();
        Integer colIdx = colIndex.get(metricFieldName);
        if (colIdx == null) {
            return translator.toInternalAggregation(agg.getName(), null);
        }

        if (accumulatedGroupFields.isEmpty()) {
            // No grouping — single row result
            Object[] row = result.getRows()[0];
            Object value = colIdx < row.length ? row[colIdx] : null;
            return translator.toInternalAggregation(agg.getName(), value);
        }

        // With grouping — find the row matching parent key filter
        Object[] matchingRow = findMatchingRow(result, colIndex, parentKeyFilter);
        Object value = matchingRow != null && colIdx < matchingRow.length ? matchingRow[colIdx] : null;
        return translator.toInternalAggregation(agg.getName(), value);
    }

    @SuppressWarnings("unchecked")
    private InternalAggregation buildBucket(
            BucketShape<AggregationBuilder> shape,
            AggregationBuilder agg,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) throws ConversionException {

        List<String> bucketFieldNames = shape.getGrouping(agg).getFieldNames();

        // Single-bucket aggregations (filter, global, etc.) have no GROUP BY columns.
        // They act as pass-through wrappers.
        if (bucketFieldNames.isEmpty()) {
            // Filters aggregation: multi-bucket via separate plans per filter
            if (agg instanceof FiltersAggregationBuilder filtersAgg) {
                return buildFiltersBuckets(shape, filtersAgg, accumulatedGroupFields, parentKeyFilter);
            }
            // Single filter / global / missing: pass-through
            return buildSingleBucket(shape, agg, accumulatedGroupFields, parentKeyFilter);
        }

        List<String> newAccumulatedFields = new ArrayList<>(accumulatedGroupFields);
        newAccumulatedFields.addAll(bucketFieldNames);

        String granularityKey = String.join(",", newAccumulatedFields);
        ExecutionResult result = granularityMap.get(granularityKey);
        if (result == null || result.getRows().length == 0) {
            return shape.toBucketAggregation(agg, List.of());
        }

        Map<String, Integer> colIndex = buildColumnIndex(result);

        // Filter rows by parent key
        List<Object[]> filteredRows = filterRows(result.getRows(), colIndex, parentKeyFilter);

        // Group filtered rows by this bucket's key columns
        Map<List<Object>, List<Object[]>> groups = groupByKeys(filteredRows, colIndex, bucketFieldNames);

        // Build bucket entries
        Integer countCol = colIndex.get(AggregationMetadataBuilder.IMPLICIT_COUNT_NAME);
        Collection<AggregationBuilder> subAggs = shape.getSubAggregations(agg);

        List<BucketEntry> bucketEntries = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Object[]>> group : groups.entrySet()) {
            List<Object> keys = group.getKey();
            List<Object[]> groupRows = group.getValue();

            // Doc count from _count column, or default to row count
            long docCount = 1;
            if (countCol != null && !groupRows.isEmpty()) {
                Object[] firstRow = groupRows.get(0);
                if (countCol < firstRow.length) {
                    Object countVal = firstRow[countCol];
                    if (countVal instanceof Number) {
                        docCount = ((Number) countVal).longValue();
                    }
                }
            }

            // Build parent key filter for sub-agg recursion
            Map<String, Object> childKeyFilter = new HashMap<>(parentKeyFilter);
            for (int i = 0; i < bucketFieldNames.size(); i++) {
                childKeyFilter.put(bucketFieldNames.get(i), keys.get(i));
            }

            // Build sub-aggregations
            InternalAggregations subAggResults;
            if (subAggs != null && !subAggs.isEmpty()) {
                List<InternalAggregation> subAggList = buildLevel(subAggs, newAccumulatedFields, childKeyFilter);
                subAggResults = InternalAggregations.from(subAggList);
            } else {
                subAggResults = InternalAggregations.EMPTY;
            }

            bucketEntries.add(new BucketEntry(keys, docCount, subAggResults));
        }

        return shape.toBucketAggregation(agg, bucketEntries);
    }

    /**
     * Handles single-bucket aggregations (filter, global, missing, etc.) that produce
     * exactly one bucket with no GROUP BY columns. These act as pass-through wrappers.
     */
    @SuppressWarnings("unchecked")
    private InternalAggregation buildSingleBucket(
            BucketShape<AggregationBuilder> shape,
            AggregationBuilder agg,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) throws ConversionException {

        Collection<AggregationBuilder> subAggs = shape.getSubAggregations(agg);

        // Build sub-aggregations — pass through accumulated fields unchanged
        InternalAggregations subAggResults;
        if (subAggs != null && !subAggs.isEmpty()) {
            List<InternalAggregation> subAggList = buildLevel(subAggs, accumulatedGroupFields, parentKeyFilter);
            subAggResults = InternalAggregations.from(subAggList);
        } else {
            subAggResults = InternalAggregations.EMPTY;
        }

        // Compute doc_count by summing _count from the nearest sub-agg granularity result
        long docCount = computeSingleBucketDocCount(accumulatedGroupFields, subAggs, parentKeyFilter);

        BucketEntry entry = new BucketEntry(List.of(), docCount, subAggResults);
        return shape.toBucketAggregation(agg, List.of(entry));
    }

    /**
     * Handles the {@code filters} aggregation by collecting results from multiple
     * granularities (one per filter) and assembling them into bucket entries.
     *
     * <p>Each filter in the filters aggregation was expanded by the tree walker into
     * a separate granularity with a unique {@code filtersAggIndex}. This method
     * looks up each filter's results and builds the corresponding bucket entry.
     */
    @SuppressWarnings("unchecked")
    private InternalAggregation buildFiltersBuckets(
            BucketShape<AggregationBuilder> shape,
            FiltersAggregationBuilder filtersAgg,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) throws ConversionException {

        List<KeyedFilter> filters = filtersAgg.filters();
        Collection<AggregationBuilder> subAggs = shape.getSubAggregations(filtersAgg);

        // The base key is the accumulated group fields (without __filters_ suffix)
        String baseKey = String.join(",", accumulatedGroupFields);
        Map<Integer, ExecutionResult> filterResults = filtersGranularityMap.getOrDefault(baseKey, Map.of());

        List<BucketEntry> bucketEntries = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++) {
            ExecutionResult filterResult = filterResults.get(i);

            // Build sub-aggregations for this filter bucket using the filter-specific
            // accumulated fields (with __filters_ suffix for granularity key matching)
            List<String> filterAccFields = new ArrayList<>(accumulatedGroupFields);
            filterAccFields.add("__filters_" + filtersAgg.getName() + "_" + i);

            InternalAggregations subAggResults;
            if (subAggs != null && !subAggs.isEmpty()) {
                List<InternalAggregation> subAggList = buildLevel(subAggs, filterAccFields, parentKeyFilter);
                subAggResults = InternalAggregations.from(subAggList);
            } else {
                subAggResults = InternalAggregations.EMPTY;
            }

            // Compute doc_count from the filter's result
            long docCount = 0;
            if (filterResult != null && filterResult.getRows().length > 0) {
                docCount = computeDocCountFromResult(filterResult, parentKeyFilter);
            }

            bucketEntries.add(new BucketEntry(List.of(), docCount, subAggResults));
        }

        return shape.toBucketAggregation(filtersAgg, bucketEntries);
    }

    /**
     * Computes the doc_count for a single-bucket aggregation by summing the _count
     * values from the nearest sub-aggregation granularity result.
     */
    @SuppressWarnings("unchecked")
    private long computeSingleBucketDocCount(
            List<String> accumulatedGroupFields,
            Collection<AggregationBuilder> subAggs,
            Map<String, Object> parentKeyFilter) throws ConversionException {

        if (subAggs == null || subAggs.isEmpty()) return 0;

        for (AggregationBuilder subAgg : subAggs) {
            AggregationType<AggregationBuilder> subType = registry.findHandler(subAgg);
            if (subType instanceof BucketShape) {
                BucketShape<AggregationBuilder> subShape = (BucketShape<AggregationBuilder>) subType;
                List<String> subFields = new ArrayList<>(accumulatedGroupFields);
                subFields.addAll(subShape.getGrouping(subAgg).getFieldNames());
                String key = String.join(",", subFields);
                ExecutionResult result = granularityMap.get(key);
                if (result != null && result.getRows().length > 0) {
                    return computeDocCountFromResult(result, parentKeyFilter);
                }
            }
        }
        return 0;
    }

    /**
     * Computes doc_count by summing _count column values from a result,
     * optionally filtered by parent key.
     */
    private static long computeDocCountFromResult(ExecutionResult result, Map<String, Object> parentKeyFilter) {
        Map<String, Integer> colIndex = buildColumnIndex(result);
        Integer countCol = colIndex.get(AggregationMetadataBuilder.IMPLICIT_COUNT_NAME);
        if (countCol == null) {
            return result.getRows().length;
        }
        List<Object[]> rows = filterRows(result.getRows(), colIndex, parentKeyFilter);
        long total = 0;
        for (Object[] row : rows) {
            if (countCol < row.length) {
                Object val = row[countCol];
                if (val instanceof Number) {
                    total += ((Number) val).longValue();
                }
            }
        }
        return total;
    }

    private static Map<String, Integer> buildColumnIndex(ExecutionResult result) {
        Map<String, Integer> index = new HashMap<>();
        List<String> fieldNames = result.getFieldNames();
        for (int i = 0; i < fieldNames.size(); i++) {
            index.put(fieldNames.get(i), i);
        }
        return index;
    }

    private static Object[] findMatchingRow(ExecutionResult result, Map<String, Integer> colIndex,
            Map<String, Object> keyFilter) {
        for (Object[] row : result.getRows()) {
            if (rowMatchesFilter(row, colIndex, keyFilter)) {
                return row;
            }
        }
        return null;
    }

    private static List<Object[]> filterRows(Object[][] rows, Map<String, Integer> colIndex,
            Map<String, Object> keyFilter) {
        if (keyFilter.isEmpty()) {
            return List.of(rows);
        }
        List<Object[]> filtered = new ArrayList<>();
        for (Object[] row : rows) {
            if (rowMatchesFilter(row, colIndex, keyFilter)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private static boolean rowMatchesFilter(Object[] row, Map<String, Integer> colIndex,
            Map<String, Object> keyFilter) {
        for (Map.Entry<String, Object> entry : keyFilter.entrySet()) {
            Integer col = colIndex.get(entry.getKey());
            if (col == null) return false;
            Object rowVal = row[col];
            Object filterVal = entry.getValue();
            if (!valuesEqual(rowVal, filterVal)) return false;
        }
        return true;
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // Handle numeric type mismatches (e.g., Long vs Integer)
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private static Map<List<Object>, List<Object[]>> groupByKeys(
            List<Object[]> rows, Map<String, Integer> colIndex, List<String> keyFieldNames) {
        Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] row : rows) {
            List<Object> key = new ArrayList<>(keyFieldNames.size());
            for (String fieldName : keyFieldNames) {
                Integer col = colIndex.get(fieldName);
                key.add(col != null ? row[col] : null);
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private static String granularityKey(ExecutionResult result) {
        if (result.getAggregationMetadata() == null) return "";
        List<String> groupByFields = result.getAggregationMetadata().getGroupByFieldNames();
        if (groupByFields.isEmpty()) return "";
        return groupByFields.stream().collect(Collectors.joining(","));
    }

    /**
     * Strips the {@code __filters_*} suffix from a granularity key to get the base key.
     * For example, {@code "name,__filters_myAgg_0"} becomes {@code "name"}.
     */
    private static String stripFiltersKeySuffix(String key) {
        // Remove all __filters_* segments from the comma-separated key
        String[] parts = key.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.startsWith("__filters_")) {
                if (sb.length() > 0) sb.append(",");
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /**
     * Looks up an execution result by granularity key, checking both the regular
     * granularity map and the filters granularity map.
     */
    private ExecutionResult lookupResult(String granularityKey) {
        ExecutionResult result = granularityMap.get(granularityKey);
        if (result != null) return result;

        // Check if this key contains a __filters_ marker — if so, look up in filtersGranularityMap
        if (granularityKey.contains("__filters_")) {
            String baseKey = stripFiltersKeySuffix(granularityKey);
            Map<Integer, ExecutionResult> filterResults = filtersGranularityMap.get(baseKey);
            if (filterResults != null) {
                // Extract the filter index from the key
                int idx = extractFiltersIndex(granularityKey);
                if (idx >= 0) {
                    return filterResults.get(idx);
                }
            }
        }
        return null;
    }

    /**
     * Extracts the filter index from a granularity key containing a __filters_ marker.
     * For example, {@code "__filters_myAgg_2"} returns 2.
     */
    private static int extractFiltersIndex(String granularityKey) {
        String[] parts = granularityKey.split(",");
        for (String part : parts) {
            if (part.startsWith("__filters_")) {
                int lastUnderscore = part.lastIndexOf('_');
                if (lastUnderscore > 0) {
                    try {
                        return Integer.parseInt(part.substring(lastUnderscore + 1));
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }
}
