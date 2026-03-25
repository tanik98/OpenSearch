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
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregator;
import org.opensearch.search.aggregations.metrics.AvgAggregationBuilder;
import org.opensearch.search.aggregations.metrics.SumAggregationBuilder;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class FiltersBucketTranslatorTests extends OpenSearchTestCase {

    private final QueryRegistry queryRegistry = QueryRegistryFactory.create();
    private final FiltersBucketTranslator translator = new FiltersBucketTranslator(queryRegistry);
    private final ConversionContext ctx = TestUtils.createContext();

    public void testGetAggregationType() {
        assertEquals(FiltersAggregationBuilder.class, translator.getAggregationType());
    }

    public void testGetGroupingReturnsEmpty() throws ConversionException {
        FiltersAggregationBuilder agg = new FiltersAggregationBuilder(
            "messages",
            new FiltersAggregator.KeyedFilter("errors", new TermQueryBuilder("brand", "test"))
        );
        GroupingInfo grouping = translator.getGrouping(agg);

        assertTrue(grouping.getFieldNames().isEmpty());
        assertTrue(grouping.resolveIndices(ctx.getRowType()).isEmpty());
    }

    public void testGetSubAggregationsReturnsNestedAggs() {
        FiltersAggregationBuilder agg = new FiltersAggregationBuilder(
            "messages",
            new FiltersAggregator.KeyedFilter("errors", new TermQueryBuilder("brand", "test"))
        ).subAggregation(new AvgAggregationBuilder("avg_price").field("price"))
            .subAggregation(new SumAggregationBuilder("total_price").field("price"));

        assertEquals(2, translator.getSubAggregations(agg).size());
    }

    public void testGetSubAggregationsReturnsEmptyWhenNone() {
        FiltersAggregationBuilder agg = new FiltersAggregationBuilder(
            "messages",
            new FiltersAggregator.KeyedFilter("errors", new TermQueryBuilder("brand", "test"))
        );

        assertTrue(translator.getSubAggregations(agg).isEmpty());
    }

    public void testGetKeyedFiltersWithNamedFilters() throws ConversionException {
        FiltersAggregationBuilder agg = new FiltersAggregationBuilder(
            "messages",
            new FiltersAggregator.KeyedFilter("errors", new TermQueryBuilder("brand", "error")),
            new FiltersAggregator.KeyedFilter("warnings", new TermQueryBuilder("brand", "warning"))
        );

        List<FiltersAggregator.KeyedFilter> filters = translator.getKeyedFilters(agg);
        assertEquals(2, filters.size());
        // KeyedFilter constructor with KeyedFilter... sorts by key alphabetically
        assertEquals("errors", filters.get(0).key());
        assertEquals("warnings", filters.get(1).key());
    }

    public void testGetKeyedFiltersWithAnonymousFilters() throws ConversionException {
        FiltersAggregationBuilder agg = new FiltersAggregationBuilder(
            "status_codes",
            new TermQueryBuilder("brand", "first"),
            new TermQueryBuilder("brand", "second")
        );

        List<FiltersAggregator.KeyedFilter> filters = translator.getKeyedFilters(agg);
        assertEquals(2, filters.size());
        // Anonymous filters get positional keys "0", "1", etc.
        assertEquals("0", filters.get(0).key());
        assertEquals("1", filters.get(1).key());
    }

    public void testConvertFilterWithTermQuery() throws ConversionException {
        TermQueryBuilder termQuery = new TermQueryBuilder("brand", "test");

        RexNode condition = translator.convertFilter(termQuery, ctx);

        assertNotNull(condition);
        assertTrue(condition instanceof RexCall);
        assertEquals(SqlKind.EQUALS, condition.getKind());
    }

    public void testBuildOtherBucketCondition() throws ConversionException {
        // Create two filter conditions from term queries
        RexNode filter1 = translator.convertFilter(new TermQueryBuilder("brand", "a"), ctx);
        RexNode filter2 = translator.convertFilter(new TermQueryBuilder("brand", "b"), ctx);

        RexNode otherCondition = translator.buildOtherBucketCondition(List.of(filter1, filter2), ctx);

        assertNotNull(otherCondition);
        // Should be NOT(OR(f1, f2))
        assertTrue(otherCondition instanceof RexCall);
        assertEquals(SqlKind.NOT, otherCondition.getKind());

        RexCall notCall = (RexCall) otherCondition;
        assertEquals(1, notCall.getOperands().size());
        RexNode orNode = notCall.getOperands().get(0);
        assertTrue(orNode instanceof RexCall);
        assertEquals(SqlKind.OR, orNode.getKind());

        RexCall orCall = (RexCall) orNode;
        assertEquals(2, orCall.getOperands().size());
    }

    public void testBuildOtherBucketConditionSingleFilter() throws ConversionException {
        // Single filter: should produce NOT(f1) directly (no OR wrapper)
        RexNode filter1 = translator.convertFilter(new TermQueryBuilder("brand", "a"), ctx);

        RexNode otherCondition = translator.buildOtherBucketCondition(List.of(filter1), ctx);

        assertNotNull(otherCondition);
        assertTrue(otherCondition instanceof RexCall);
        assertEquals(SqlKind.NOT, otherCondition.getKind());

        RexCall notCall = (RexCall) otherCondition;
        assertEquals(1, notCall.getOperands().size());
        // The operand should be the filter itself (no OR wrapping for single filter)
        RexNode operand = notCall.getOperands().get(0);
        assertEquals(SqlKind.EQUALS, operand.getKind());
    }

    public void testToBucketAggregationNotYetImplemented() {
        FiltersAggregationBuilder agg = new FiltersAggregationBuilder(
            "messages",
            new FiltersAggregator.KeyedFilter("errors", new TermQueryBuilder("brand", "test"))
        );
        expectThrows(UnsupportedOperationException.class, () -> translator.toBucketAggregation(agg, List.of()));
    }
}
