/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.sandbox.search.DocValuesMultiRangeQuery;
import org.apache.lucene.sandbox.search.MultiRangeQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PointRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.opensearch.Version;
import org.opensearch.common.Explicit;
import org.opensearch.common.Nullable;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.logging.DeprecationLogger;
import org.opensearch.common.network.InetAddresses;
import org.opensearch.common.network.NetworkAddress;
import org.opensearch.index.compositeindex.datacube.DimensionType;
import org.opensearch.index.fielddata.IndexFieldData;
import org.opensearch.index.fielddata.ScriptDocValues;
import org.opensearch.index.fielddata.plain.SortedSetOrdinalsIndexFieldData;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.support.CoreValuesSourceType;
import org.opensearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.net.InetAddress;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A {@link FieldMapper} for ip addresses.
 *
 * @opensearch.internal
 */
public class IpFieldMapper extends ParametrizedFieldMapper {

    private static final DeprecationLogger DEPRECATION_LOGGER = DeprecationLogger.getLogger(IpFieldMapper.class);

    public static final String CONTENT_TYPE = "ip";

    private static IpFieldMapper toType(FieldMapper in) {
        return (IpFieldMapper) in;
    }

    /**
     * Builder
     *
     * @opensearch.internal
     */
    public static class Builder extends ParametrizedFieldMapper.Builder {

        private final Parameter<Boolean> indexed = Parameter.indexParam(m -> toType(m).indexed, true);
        private final Parameter<Boolean> hasDocValues = Parameter.docValuesParam(m -> toType(m).hasDocValues, true);
        private final Parameter<Boolean> stored = Parameter.storeParam(m -> toType(m).stored, false);

        private final Parameter<Explicit<Boolean>> ignoreMalformed;
        private final Parameter<String> nullValue = Parameter.stringParam("null_value", false, m -> toType(m).nullValueAsString, null)
            .acceptsNull();

        private final Parameter<Map<String, String>> meta = Parameter.metaParam();

        private final boolean ignoreMalformedByDefault;
        private final Version indexCreatedVersion;

        public Builder(String name, boolean ignoreMalformedByDefault, Version indexCreatedVersion) {
            super(name);
            this.ignoreMalformedByDefault = ignoreMalformedByDefault;
            this.indexCreatedVersion = indexCreatedVersion;
            this.ignoreMalformed = Parameter.explicitBoolParam(
                "ignore_malformed",
                true,
                m -> toType(m).ignoreMalformed,
                ignoreMalformedByDefault
            );
        }

        Builder nullValue(String nullValue) {
            this.nullValue.setValue(nullValue);
            return this;
        }

        private InetAddress parseNullValue() {
            String nullValueAsString = nullValue.getValue();
            if (nullValueAsString == null) {
                return null;
            }
            try {
                return InetAddresses.forString(nullValueAsString);
            } catch (Exception e) {
                DEPRECATION_LOGGER.deprecate(
                    "ip_mapper_null_field",
                    "Error parsing ["
                        + nullValue.getValue()
                        + "] as IP in [null_value] on field ["
                        + name()
                        + "]); [null_value] will be ignored"
                );
                return null;
            }
        }

        @Override
        protected List<Parameter<?>> getParameters() {
            return Arrays.asList(indexed, hasDocValues, stored, ignoreMalformed, nullValue, meta);
        }

        @Override
        public IpFieldMapper build(BuilderContext context) {
            return new IpFieldMapper(
                name,
                new IpFieldType(
                    buildFullName(context),
                    indexed.getValue(),
                    stored.getValue(),
                    hasDocValues.getValue(),
                    parseNullValue(),
                    meta.getValue()
                ),
                multiFieldsBuilder.build(this, context),
                copyTo.build(),
                this
            );
        }

        @Override
        public Optional<DimensionType> getSupportedDataCubeDimensionType() {
            return Optional.of(DimensionType.IP);
        }

    }

    public static final TypeParser PARSER = new TypeParser((n, c) -> {
        boolean ignoreMalformedByDefault = IGNORE_MALFORMED_SETTING.get(c.getSettings());
        return new Builder(n, ignoreMalformedByDefault, c.indexVersionCreated());
    });

    @Override
    protected void canDeriveSourceInternal() {
        checkStoredAndDocValuesForDerivedSource();
    }

    /**
     * 1. If it has doc values, build source using doc values
     * 2. If doc_values is disabled in field mapping, then build source using stored field
     * <p>
     * Considerations:
     *    1. When using doc values, for multi value field, result would be deduplicated and in sorted order
     *    2. When using stored field, order and duplicate values would be preserved
     */
    @Override
    protected DerivedFieldGenerator derivedFieldGenerator() {
        return new DerivedFieldGenerator(
            mappedFieldType,
            new SortedSetDocValuesFetcher(mappedFieldType, simpleName()),
            new StoredFieldFetcher(mappedFieldType, simpleName())
        );
    }

    /**
     * Field type for IP fields
     *
     * @opensearch.internal
     */
    public static final class IpFieldType extends SimpleMappedFieldType {

        private final InetAddress nullValue;

        public IpFieldType(
            String name,
            boolean indexed,
            boolean stored,
            boolean hasDocValues,
            InetAddress nullValue,
            Map<String, String> meta
        ) {
            super(name, indexed, stored, hasDocValues, TextSearchInfo.SIMPLE_MATCH_ONLY, meta);
            this.nullValue = nullValue;
        }

        public IpFieldType(String name) {
            this(name, true, false, true, null, Collections.emptyMap());
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        private static InetAddress parse(Object value) {
            if (value instanceof InetAddress) {
                return (InetAddress) value;
            } else {
                if (value instanceof BytesRef) {
                    value = ((BytesRef) value).utf8ToString();
                }
                return InetAddresses.forString(value.toString());
            }
        }

        @Override
        public ValueFetcher valueFetcher(QueryShardContext context, SearchLookup searchLookup, String format) {
            if (format != null) {
                throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
            }
            return new SourceValueFetcher(name(), context, nullValue) {
                @Override
                protected Object parseSourceValue(Object value) {
                    InetAddress address;
                    if (value instanceof InetAddress) {
                        address = (InetAddress) value;
                    } else {
                        address = InetAddresses.forString(value.toString());
                    }
                    return InetAddresses.toAddrString(address);
                }
            };
        }

        @Override
        public Query termQuery(Object value, @Nullable QueryShardContext context) {
            failIfNotIndexedAndNoDocValues();
            final PointRangeQuery pointQuery;
            if (value instanceof InetAddress) {
                pointQuery = (PointRangeQuery) InetAddressPoint.newExactQuery(name(), (InetAddress) value);
            } else {
                if (value instanceof BytesRef) {
                    value = ((BytesRef) value).utf8ToString();
                }
                String term = value.toString();
                if (term.contains("/")) {
                    final Tuple<InetAddress, Integer> cidr = InetAddresses.parseCidr(term);
                    pointQuery = (PointRangeQuery) InetAddressPoint.newPrefixQuery(name(), cidr.v1(), cidr.v2());
                } else {
                    InetAddress address = InetAddresses.forString(term);
                    pointQuery = (PointRangeQuery) InetAddressPoint.newExactQuery(name(), address);
                }
            }
            Query dvQuery = null;
            if (hasDocValues()) {
                dvQuery = SortedSetDocValuesField.newSlowRangeQuery(
                    name(),
                    new BytesRef(pointQuery.getLowerPoint()),
                    new BytesRef(pointQuery.getUpperPoint()),
                    true,
                    true
                );
            }
            if (isSearchable() && hasDocValues()) {
                return new IndexOrDocValuesQuery(pointQuery, dvQuery);
            } else {
                return isSearchable() ? pointQuery : dvQuery;
            }
        }

        @Override
        public Query termsQuery(List<?> values, QueryShardContext context) {
            failIfNotIndexedAndNoDocValues();

            List<InetAddress> concreteIPs = new ArrayList<>();
            List<PointRangeQuery> masks = new ArrayList<>();
            parseIps(values, concreteIPs, masks);

            if (!isSearchable()) {
                return hasDocValues() ? docValuesTermsQuery(concreteIPs, masks) : new MatchNoDocsQuery("never happened");
            }

            if (!hasDocValues()) {
                return indexTermsQuery(concreteIPs, masks);
            }

            // Both searchable and doc values available - create composite query
            return new IndexOrDocValuesQuery(indexTermsQuery(concreteIPs, masks), docValuesTermsQuery(concreteIPs, masks));
        }

        private void parseIps(List<?> values, List<InetAddress> concreteIPs, List<PointRangeQuery> masks) {
            for (Object value : values) {
                if (value instanceof InetAddress) {
                    concreteIPs.add((InetAddress) value);
                    continue;
                }

                String strVal = value instanceof BytesRef ? ((BytesRef) value).utf8ToString() : value.toString();

                if (strVal.contains("/")) {
                    Tuple<InetAddress, Integer> cidr = InetAddresses.parseCidr(strVal);
                    masks.add((PointRangeQuery) InetAddressPoint.newPrefixQuery(name(), cidr.v1(), cidr.v2()));
                } else {
                    concreteIPs.add(InetAddresses.forString(strVal));
                }
            }
        }

        private Query indexTermsQuery(List<InetAddress> concreteIPs, List<PointRangeQuery> masks) {
            List<Query> queries = new ArrayList<>();
            addConcreteIpQuery(concreteIPs, queries);
            addMaskQueries(masks, queries);

            return combineQueries(queries);
        }

        private void addConcreteIpQuery(List<InetAddress> ips, List<Query> queries) {
            if (ips.isEmpty()) return;

            queries.add(
                ips.size() == 1
                    ? InetAddressPoint.newExactQuery(name(), ips.getFirst())
                    : InetAddressPoint.newSetQuery(name(), ips.toArray(new InetAddress[0]))
            );
        }

        private void addMaskQueries(List<PointRangeQuery> masks, List<Query> queries) {
            if (masks.isEmpty()) return;

            if (masks.size() == 1) {
                queries.add(masks.getFirst());
            } else {
                MultiIpRangeQueryBuilder multiRange = new MultiIpRangeQueryBuilder(name());
                masks.forEach(q -> multiRange.add(q.getLowerPoint(), q.getUpperPoint()));
                queries.add(multiRange.build());
            }
        }

        private Query combineQueries(List<Query> queries) {
            return switch (queries.size()) {
                case 0 -> new MatchNoDocsQuery();
                case 1 -> queries.getFirst();
                default -> new ConstantScoreQuery(union(queries));
            };
        }

        private Query docValuesTermsQuery(List<InetAddress> concreteIPs, List<PointRangeQuery> masks) {
            List<BytesRef> ipsBytes = concreteIPs.stream().map(addr -> new BytesRef(InetAddressPoint.encode(addr))).toList();

            if (ipsBytes.isEmpty() && masks.isEmpty()) {
                return new MatchNoDocsQuery();
            }
            if (masks.isEmpty()) {
                if (ipsBytes.size() == 1) {
                    return SortedSetDocValuesField.newSlowExactQuery(name(), ipsBytes.getFirst());
                } else {
                    return SortedSetDocValuesField.newSlowSetQuery(name(), ipsBytes);
                }
            } else {
                if (masks.size() == 1 && ipsBytes.isEmpty()) {
                    return SortedSetDocValuesField.newSlowRangeQuery(
                        name(),
                        new BytesRef(masks.getFirst().getLowerPoint()),
                        new BytesRef(masks.getFirst().getUpperPoint()),
                        true,
                        true
                    );
                } else {
                    DocValuesMultiRangeQuery.SortedSetStabbingBuilder builder = new DocValuesMultiRangeQuery.SortedSetStabbingBuilder(
                        name()
                    );
                    masks.forEach(q -> builder.add(new BytesRef(q.getLowerPoint()), new BytesRef(q.getUpperPoint())));
                    ipsBytes.forEach(builder::add);
                    return builder.build();
                }
            }
        }

        private Query union(List<Query> combiner) {
            BooleanQuery.Builder bqb = new BooleanQuery.Builder();
            for (Query q : combiner) {
                bqb.add(q, BooleanClause.Occur.SHOULD);
            }
            return bqb.build();
        }

        @Override
        public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper, QueryShardContext context) {
            failIfNotIndexedAndNoDocValues();
            return rangeQuery(lowerTerm, upperTerm, includeLower, includeUpper, (lower, upper) -> {
                PointRangeQuery pointQuery = (PointRangeQuery) InetAddressPoint.newRangeQuery(name(), lower, upper);
                Query dvQuery = null;
                if (hasDocValues()) {
                    dvQuery = SortedSetDocValuesField.newSlowRangeQuery(
                        pointQuery.getField(),
                        new BytesRef(pointQuery.getLowerPoint()),
                        new BytesRef(pointQuery.getUpperPoint()),
                        true,
                        true
                    );
                }
                if (isSearchable() && hasDocValues()) {
                    return new IndexOrDocValuesQuery(pointQuery, dvQuery);
                } else {
                    return isSearchable() ? pointQuery : dvQuery;
                }
            });
        }

        /**
         * Processes query bounds into {@code long}s and delegates the
         * provided {@code builder} to build a range query.
         */
        public static Query rangeQuery(
            Object lowerTerm,
            Object upperTerm,
            boolean includeLower,
            boolean includeUpper,
            BiFunction<InetAddress, InetAddress, Query> builder
        ) {
            InetAddress lower;
            if (lowerTerm == null) {
                lower = InetAddressPoint.MIN_VALUE;
            } else {
                lower = parse(lowerTerm);
                if (includeLower == false) {
                    if (lower.equals(InetAddressPoint.MAX_VALUE)) {
                        return new MatchNoDocsQuery();
                    }
                    lower = InetAddressPoint.nextUp(lower);
                }
            }

            InetAddress upper;
            if (upperTerm == null) {
                upper = InetAddressPoint.MAX_VALUE;
            } else {
                upper = parse(upperTerm);
                if (includeUpper == false) {
                    if (upper.equals(InetAddressPoint.MIN_VALUE)) {
                        return new MatchNoDocsQuery();
                    }
                    upper = InetAddressPoint.nextDown(upper);
                }
            }

            return builder.apply(lower, upper);
        }

        /**
         * Field type for IP Scripted doc values
         *
         * @opensearch.internal
         */
        public static final class IpScriptDocValues extends ScriptDocValues<String> {

            private final SortedSetDocValues in;
            private long[] ords = new long[0];
            private int count;

            public IpScriptDocValues(SortedSetDocValues in) {
                this.in = in;
            }

            @Override
            public void setNextDocId(int docId) throws IOException {
                count = 0;
                if (in.advanceExact(docId)) {
                    for (long ord = in.nextOrd(); ord != SortedSetDocValues.NO_MORE_DOCS && count < in.docValueCount(); ord = in
                        .nextOrd()) {
                        ords = ArrayUtil.grow(ords, count + 1);
                        ords[count++] = ord;
                    }
                }
            }

            public String getValue() {
                if (count == 0) {
                    return null;
                } else {
                    return get(0);
                }
            }

            @Override
            public String get(int index) {
                try {
                    BytesRef encoded = in.lookupOrd(ords[index]);
                    InetAddress address = InetAddressPoint.decode(
                        Arrays.copyOfRange(encoded.bytes, encoded.offset, encoded.offset + encoded.length)
                    );
                    return InetAddresses.toAddrString(address);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public int size() {
                return count;
            }
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(String fullyQualifiedIndexName, Supplier<SearchLookup> searchLookup) {
            failIfNoDocValues();
            return new SortedSetOrdinalsIndexFieldData.Builder(name(), IpScriptDocValues::new, CoreValuesSourceType.IP);
        }

        @Override
        public Object valueForDisplay(Object value) {
            if (value == null) {
                return null;
            }
            return DocValueFormat.IP.format((BytesRef) value);
        }

        @Override
        public DocValueFormat docValueFormat(@Nullable String format, ZoneId timeZone) {
            if (format != null) {
                throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] does not support custom formats");
            }
            if (timeZone != null) {
                throw new IllegalArgumentException(
                    "Field [" + name() + "] of type [" + typeName() + "] does not support custom time zones"
                );
            }
            return DocValueFormat.IP;
        }
    }

    /**
     * Union over IP address ranges
     */
    public static class MultiIpRangeQueryBuilder extends MultiRangeQuery.Builder {
        public MultiIpRangeQueryBuilder(String field) {
            super(field, InetAddressPoint.BYTES, 1);
        }

        public MultiIpRangeQueryBuilder add(InetAddress lower, InetAddress upper) {
            add(new MultiRangeQuery.RangeClause(InetAddressPoint.encode(lower), InetAddressPoint.encode(upper)));
            return this;
        }

        @Override
        public MultiRangeQuery build() {
            return new MultiRangeQuery(field, numDims, bytesPerDim, clauses) {
                @Override
                protected String toString(int dimension, byte[] value) {
                    return NetworkAddress.format(InetAddressPoint.decode(value));
                }
            };
        }
    }

    private final boolean indexed;
    private final boolean hasDocValues;
    private final boolean stored;
    private final Explicit<Boolean> ignoreMalformed;

    private final InetAddress nullValue;
    private final String nullValueAsString;

    private final boolean ignoreMalformedByDefault;
    private final Version indexCreatedVersion;

    private IpFieldMapper(String simpleName, MappedFieldType mappedFieldType, MultiFields multiFields, CopyTo copyTo, Builder builder) {
        super(simpleName, mappedFieldType, multiFields, copyTo);
        this.ignoreMalformedByDefault = builder.ignoreMalformedByDefault;
        this.indexed = builder.indexed.getValue();
        this.hasDocValues = builder.hasDocValues.getValue();
        this.stored = builder.stored.getValue();
        this.ignoreMalformed = builder.ignoreMalformed.getValue();
        this.nullValue = builder.parseNullValue();
        this.nullValueAsString = builder.nullValue.getValue();
        this.indexCreatedVersion = builder.indexCreatedVersion;
    }

    @Override
    protected Explicit<Boolean> ignoreMalformed() {
        return ignoreMalformed;
    }

    @Override
    public IpFieldType fieldType() {
        return (IpFieldType) super.fieldType();
    }

    @Override
    protected String contentType() {
        return fieldType().typeName();
    }

    @Override
    protected IpFieldMapper clone() {
        return (IpFieldMapper) super.clone();
    }

    @Override
    protected void parseCreateField(ParseContext context) throws IOException {
        Object addressAsObject;
        if (context.externalValueSet()) {
            addressAsObject = context.externalValue();
        } else {
            addressAsObject = context.parser().textOrNull();
        }

        if (addressAsObject == null) {
            addressAsObject = nullValue;
        }

        if (addressAsObject == null) {
            return;
        }

        String addressAsString = addressAsObject.toString();
        InetAddress address;
        if (addressAsObject instanceof InetAddress) {
            address = (InetAddress) addressAsObject;
        } else {
            try {
                address = InetAddresses.forString(addressAsString);
            } catch (IllegalArgumentException e) {
                if (ignoreMalformed().value()) {
                    context.addIgnoredField(fieldType().name());
                    return;
                } else {
                    throw e;
                }
            }
        }

        if (indexed && hasDocValues) {
            context.doc().add(new InetAddressField(fieldType().name(), address));
        } else if (indexed) {
            context.doc().add(new InetAddressPoint(fieldType().name(), address));
        } else if (hasDocValues) {
            context.doc().add(new SortedSetDocValuesField(fieldType().name(), new BytesRef(InetAddressPoint.encode(address))));
        }
        if ((stored || indexed) && hasDocValues == false) {
            createFieldNamesField(context);
        }
        if (stored) {
            context.doc().add(new StoredField(fieldType().name(), new BytesRef(InetAddressPoint.encode(address))));
        }
    }

    public static InetAddressField buildInetAddressField(String name, InetAddress value) {
        return new InetAddressField(name, value);
    }

    /**
     * Field type that combines dimensional points and doc values for IP fields
     */
    public static class InetAddressField extends Field {
        private static final FieldType FIELD_TYPE = new FieldType();
        static {
            FIELD_TYPE.setDimensions(1, InetAddressPoint.BYTES);
            FIELD_TYPE.setDocValuesType(DocValuesType.SORTED_SET);
            FIELD_TYPE.freeze();
        }

        /**
         * Create new instance
         */
        public InetAddressField(String name, InetAddress value) {
            super(name, FIELD_TYPE);
            fieldsData = new BytesRef(InetAddressPoint.encode(value));
        }
    }

    @Override
    public ParametrizedFieldMapper.Builder getMergeBuilder() {
        return new Builder(simpleName(), ignoreMalformedByDefault, indexCreatedVersion).init(this);
    }
}
