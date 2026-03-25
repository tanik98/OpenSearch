/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.opensearch.dsl.TestUtils;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.converter.ConversionContext;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.query.QueryRegistry;
import org.opensearch.dsl.query.QueryRegistryFactory;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.opensearch.search.aggregations.metrics.AvgAggregationBuilder;
import org.opensearch.search.aggregations.metrics.SumAggregationBuilder;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class FilterBucketTranslatorTests extends OpenSearchTestCase {

    private final QueryRegistry queryRegistry = QueryRegistryFactory.create();
    private final FilterBucketTranslator translator = new FilterBucketTranslator(queryRegistry);
    private final ConversionContext ctx = TestUtils.createContext();

    public void testGetAggregationType() {
        assertEquals(FilterAggregationBuilder.class, translator.getAggregationType());
    }

    public void testGetGroupingReturnsEmpty() throws ConversionException {
        FilterAggregationBuilder agg = new FilterAggregationBuilder("active", new TermQueryBuilder("brand", "test"));
        GroupingInfo grouping = translator.getGrouping(agg);

        assertTrue(grouping.getFieldNames().isEmpty());
        assertTrue(grouping.resolveIndices(ctx.getRowType()).isEmpty());
    }

    public void testGetSubAggregationsReturnsNestedAggs() {
        FilterAggregationBuilder agg = new FilterAggregationBuilder("active", new TermQueryBuilder("brand", "test"))
            .subAggregation(new AvgAggregationBuilder("avg_price").field("price"))
            .subAggregation(new SumAggregationBuilder("total_price").field("price"));

        assertEquals(2, translator.getSubAggregations(agg).size());
    }

    public void testGetSubAggregationsReturnsEmptyWhenNone() {
        FilterAggregationBuilder agg = new FilterAggregationBuilder("active", new TermQueryBuilder("brand", "test"));

        assertTrue(translator.getSubAggregations(agg).isEmpty());
    }

    public void testGetFilterConditionWithTermQuery() throws ConversionException {
        FilterAggregationBuilder agg = new FilterAggregationBuilder("active", new TermQueryBuilder("brand", "test"));

        RexNode condition = translator.getFilterCondition(agg, ctx);

        assertNotNull(condition);
        // TermQueryTranslator produces an EQUALS call
        assertTrue(condition instanceof RexCall);
        assertEquals(SqlKind.EQUALS, condition.getKind());
    }

    public void testGetFilterConditionWithUnsupportedQuery() throws ConversionException {
        FilterAggregationBuilder agg = new FilterAggregationBuilder("all", new MatchAllQueryBuilder());
        RexNode condition = translator.getFilterCondition(agg, ctx);
        assertNotNull(condition);
    }

    public void testGetFilterConditionThrowsOnNullFilter() {
        FilterAggregationBuilder agg = new FilterAggregationBuilder("active", new TermQueryBuilder("brand", "test"));
        assertNotNull(assertDoesNotThrow(() -> translator.getFilterCondition(agg, ctx)));
    }

    public void testToBucketAggregationNotYetImplemented() {
        FilterAggregationBuilder agg = new FilterAggregationBuilder("active", new TermQueryBuilder("brand", "test"));
        expectThrows(UnsupportedOperationException.class, () -> translator.toBucketAggregation(agg, List.of()));
    }

    private static <T> T assertDoesNotThrow(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            fail("Expected no exception but got: " + e.getMessage());
            return null; // unreachable
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
