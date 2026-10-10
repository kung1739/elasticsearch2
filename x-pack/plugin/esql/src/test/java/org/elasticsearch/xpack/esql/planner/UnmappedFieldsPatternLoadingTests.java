/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.mapper.BlockLoader;
import org.elasticsearch.index.mapper.BlockSourceReader;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperServiceTestCase;
import org.elasticsearch.index.mapper.TestBlock;
import org.elasticsearch.index.mapper.UnmappedFields;
import org.elasticsearch.indices.CrankyCircuitBreakerService;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.esql.plan.logical.UnmappedFieldsPattern;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.MapMatcher.assertMap;
import static org.elasticsearch.test.MapMatcher.matchesMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests which unmapped fields {@link UnmappedFields#blockLoader(UnmappedFields.Filter, double)} loads for an
 * {@link UnmappedFieldsPattern}: the leaves of {@code _source} that the pattern keeps and that hold a value, each under its
 * dotted path. Everything else is dropped on the shard: the mapped fields (which the analyzer adds to the pattern's excludes),
 * any leaf that does not match the includes, and any that carries nothing for the coordinator to turn into a column.
 */
public class UnmappedFieldsPatternLoadingTests extends MapperServiceTestCase {

    /** Any value above one exercises the scaling; production reads it from {@code PlannerSettings.SOURCE_RESERVATION_FACTOR}. */
    private static final double RESERVATION_FACTOR = 4;

    public void testFiltersOutMappedFieldsKeepingUnmappedSourceKeys() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.excludes(List.of("emp_no", "first_name")), """
            { "emp_no": 1, "first_name": "John", "first_pet": "Rex", "hobby": "chess" }""");
        assertMap(filtered, matchesMap().entry("first_pet", "Rex").entry("hobby", "chess"));
    }

    public void testIncludeWildcardSelectsMatchingSourceKeys() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.includes(List.of("first*")), """
            { "first_name": "John", "first_pet": "Rex", "last_name": "Doe", "age": 30 }""");
        assertMap(filtered, matchesMap().entry("first_name", "John").entry("first_pet", "Rex"));
    }

    public void testIncludeWildcardWithExcludeRemovesMatchingKey() throws IOException {
        Map<String, Object> filtered = load(
            UnmappedFieldsPattern.includes(List.of("first*")).withAdditionalExcludes(List.of("first_name")),
            """
                { "first_name": "John", "first_pet": "Rex", "first_toy": "ball", "last_name": "Doe" }"""
        );
        assertMap(filtered, matchesMap().entry("first_pet", "Rex").entry("first_toy", "ball"));
    }

    public void testSingleIncludeGroupUsesOrSemantics() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.includes(List.of("first_name*", "salary_bonus*")), """
            { "first_name_suffix": "Jr", "salary_bonus": 100, "first_pet": "Rex", "last_name": "Doe" }""");
        assertMap(filtered, matchesMap().entry("first_name_suffix", "Jr").entry("salary_bonus", 100));
    }

    public void testMultipleIncludeGroupsRequireEachGroupToMatch() throws IOException {
        Map<String, Object> filtered = load(
            UnmappedFieldsPattern.includes(List.of("first*")).intersect(UnmappedFieldsPattern.includes(List.of("first_name*"))),
            """
                { "first_name_suffix": "Jr", "first_pet": "Rex", "first_grade": "A", "last_name": "Doe" }"""
        );
        assertMap(filtered, matchesMap().entry("first_name_suffix", "Jr"));
    }

    public void testExcludePatternRemovesMatchingSourceKeys() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.excludes(List.of("secret*")), """
            { "secret_key": "abc", "secret_token": "xyz", "public_note": "hello" }""");
        assertMap(filtered, matchesMap().entry("public_note", "hello"));
    }

    public void testObjectsAreFlattenedToTheirLeaves() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.excludes(List.of("first_name")), """
            { "address": { "city": "Berlin", "zip": "10115" }, "tags": [ "a", "b" ], "first_name": "John" }""");
        assertMap(filtered, matchesMap().entry("address.city", "Berlin").entry("address.zip", "10115").entry("tags", List.of("a", "b")));
    }

    /** An array of scalars is a leaf named after the array, so it is held to the pattern like a scalar is. */
    public void testScalarArrayIsGatedLikeAScalar() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.includes(List.of("tags*")), """
            { "tags": [ "a", "b" ], "other": "x" }""");
        assertMap(filtered, matchesMap().entry("tags", List.of("a", "b")));

        assertThat(load(UnmappedFieldsPattern.includes(List.of("tags.*")), """
            { "tags": [ "a", "b" ], "other": "x" }"""), nullValue());
    }

    public void testObjectIsEnteredWhenOnlyItsDescendantsCanMatch() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.includes(List.of("address.*")), """
            { "address": { "city": "Berlin" }, "other": "x" }""");
        assertMap(filtered, matchesMap().entry("address.city", "Berlin"));
    }

    /** Same pattern and key as above, but a scalar at that key is a leaf of its own, which the pattern does not keep. */
    public void testScalarUnderTheSamePatternIsGatedStrictly() throws IOException {
        assertThat(load(UnmappedFieldsPattern.includes(List.of("address.*")), """
            { "address": "scalar" }"""), nullValue());
    }

    public void testNonePatternEmitsNull() throws IOException {
        assertThat(load(UnmappedFieldsPattern.NONE, """
            { "a": "1", "b": "2" }"""), nullValue());
    }

    public void testActivePatternMatchingNothingEmitsNull() throws IOException {
        assertThat(load(UnmappedFieldsPattern.includes(List.of("nomatch*")), """
            { "first_name": "John", "hobby": "chess" }"""), nullValue());
    }

    public void testEmptySourceEmitsNull() throws IOException {
        assertThat(load(UnmappedFieldsPattern.ALL, "{}"), nullValue());
    }

    /**
     * A key whose value is null, an empty array or an array of nulls says as little about the field as omitting the key would, so it
     * is not worth a trip to the coordinator - where it would earn the field an output column that is null in every row.
     */
    public void testValuelessSourceValuesAreDropped() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            {
              "null_value": null,
              "empty_array": [],
              "array_of_null": [ null ],
              "nested_emptiness": [ [ null ], [] ],
              "hobby": "chess"
            }""");
        assertMap(filtered, matchesMap().entry("hobby", "chess"));
    }

    /**
     * Objects are not expanded into columns of their own, but a nully object still says nothing about the field it sits under, so it
     * must not keep that field's column alive either - not even through a leaf buried inside it, which would not be mapped anyway.
     */
    public void testValuelessSourceObjectsAreDropped() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            {
              "empty_object": {},
              "object_of_nully_leaves": { "baz": [ null ], "inga": {} },
              "array_of_nully_objects": [ { "foo": [ null ] }, { "bar": [] } ],
              "hobby": "chess"
            }""");
        assertMap(filtered, matchesMap().entry("hobby", "chess"));
    }

    /** An object with anything at all in it is kept, but only the parts of it that say something survive. */
    public void testSourceObjectWithAValueKeepsOnlyThatValue() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            { "extra": { "baz": "world", "empty": [], "null_leaf": null } }""");
        assertMap(filtered, matchesMap().entry("extra.baz", "world"));
    }

    public void testOnlyValuelessSourceValuesEmitsNull() throws IOException {
        assertThat(load(UnmappedFieldsPattern.ALL, """
            { "first_pet": null, "tags": [], "address": { "city": [] } }"""), nullValue());
    }

    /**
     * One real element makes an array worth keeping, but the nulls around it are not: were the field mapped, the array would have
     * become a multi-value, and multi-values never contain nulls.
     */
    public void testNullsAreStrippedFromKeptArrays() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            { "tags": [ null, "a", null, "b" ] }""");
        assertMap(filtered, matchesMap().entry("tags", List.of("a", "b")));
    }

    public void testNullsAreStrippedFromNestedArraysAndObjects() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            { "tags": [ null, [ "b", null ], [], { "keep": "me", "drop": [] } ] }""");
        assertMap(filtered, matchesMap().entry("tags", "b").entry("tags.keep", "me"));
    }

    public void testEveryLeafWithAValueIsKept() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            { "tags": [ "a", "b" ], "address": { "city": "Berlin", "zips": [ "10115" ] } }""");
        assertMap(filtered, matchesMap().entry("tags", List.of("a", "b")).entry("address.city", "Berlin").entry("address.zips", "10115"));
    }

    public void testReaderToStringIsDistinctFromLoader() throws IOException {
        BlockLoader loader = loader(UnmappedFieldsPattern.ALL);
        assertThat(loader.toString(), equalTo("UnmappedFields.All"));
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(newLimitedBreaker(ByteSizeValue.ofMb(1)), null)) {
            assertThat(reader.toString(), equalTo("UnmappedFields.All.Reader"));
        }
    }

    public void testPerDocumentSourceParsingReservesAndReleasesBreakerBytes() throws IOException {
        Source source = Source.fromMap(Map.of("payload", "x".repeat(4096)), XContentType.JSON);
        long reservation = (long) (source.internalSourceRef().length() * RESERVATION_FACTOR);
        CircuitBreaker breaker = newLimitedBreaker(ByteSizeValue.ofBytes(BlockSourceReader.ESTIMATED_SIZE + reservation));
        BlockLoader loader = loader(UnmappedFieldsPattern.ALL);
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(breaker, null)) {
            long readerReservation = breaker.getUsed();
            assertThat(readerReservation, equalTo(BlockSourceReader.ESTIMATED_SIZE));

            BlockLoader.Builder builder = loader.builder(TestBlock.factory(), 1);
            reader.read(0, storedFields(source), builder);

            assertThat(breaker.getUsed(), equalTo(readerReservation));
        }
        assertThat(breaker.getUsed(), equalTo(0L));
    }

    public void testSourceReservationIsScaledByReservationFactor() throws IOException {
        Source source = Source.fromMap(Map.of("payload", "x".repeat(4096)), XContentType.JSON);
        // Room for the raw source but not for the far larger map it parses into, so only a scaled reservation trips this.
        long limit = BlockSourceReader.ESTIMATED_SIZE + source.internalSourceRef().length();
        CircuitBreaker breaker = newLimitedBreaker(ByteSizeValue.ofBytes(limit));
        BlockLoader loader = loader(UnmappedFieldsPattern.ALL);
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(breaker, null)) {
            BlockLoader.Builder builder = loader.builder(TestBlock.factory(), 1);
            expectThrows(CircuitBreakingException.class, () -> reader.read(0, storedFields(source), builder));
            assertThat(breaker.getUsed(), equalTo(BlockSourceReader.ESTIMATED_SIZE));
        }
    }

    public void testReaderUnderCrankyBreakerDoesNotLeak() throws IOException {
        BlockLoader loader = loader(UnmappedFieldsPattern.ALL);
        Source source = Source.fromBytes(new BytesArray("""
            { "a": "b", "c": "d" }"""), XContentType.JSON);
        var cranky = new CrankyCircuitBreakerService.CrankyCircuitBreaker();
        for (int attempt = 0; attempt < 2000; attempt++) {
            try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(cranky, null)) {
                BlockLoader.Builder builder = loader.builder(TestBlock.factory(), 1);
                reader.read(0, storedFields(source), builder);
            } catch (CircuitBreakingException e) {
                // expected on some attempts
            }
            assertThat("breaker leaked on attempt " + attempt, cranky.getUsed(), equalTo(0L));
        }
    }

    /**
     * A nested object reads from the root document like any array of objects: a field its mapping does not declare holds
     * the values of all the nested objects. The fields it maps are left out, like any mapped field.
     */
    public void testBelowANestedObjectUndeclaredFieldsAreLoaded() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            { "item": [ { "value": 1, "extra": "x" }, { "value": 2, "extra": "y" } ], "other": "z" }""");
        assertMap(filtered, matchesMap().entry("item.value", List.of(1, 2)).entry("other", "z"));

        assertThat(load(UnmappedFieldsPattern.ALL, """
            { "item": [ { "extra": "x" } ] }"""), nullValue());
    }

    /**
     * A mapped field keeps its whole value under its own name, so the keys of an object it holds are not fields.
     */
    public void testNothingBelowAMappedFieldIsLoaded() throws IOException {
        Map<String, Object> filtered = load(UnmappedFieldsPattern.ALL, """
            { "span": { "gte": 1, "lt": 5 }, "labels": { "env": "prod" }, "other": { "gte": 1 } }""");
        assertMap(filtered, matchesMap().entry("other.gte", 1));
    }

    /**
     * The loader of a shard whose mapping keeps the fields it does not declare. It maps a nested object {@code item} with a
     * field {@code extra}, an {@code integer_range} field {@code span} and a {@code flattened} field {@code labels}.
     */
    private BlockLoader loader(UnmappedFieldsPattern pattern) throws IOException {
        MapperService mapperService = createMapperService(topMapping(b -> {
            b.field("dynamic", false);
            b.startObject("properties");
            b.startObject("item").field("type", "nested").startObject("properties");
            b.startObject("extra").field("type", "keyword").endObject();
            b.endObject().endObject();
            b.startObject("span").field("type", "integer_range").endObject();
            b.startObject("labels").field("type", "flattened").endObject();
            b.endObject();
        }));
        return createSearchExecutionContext(mapperService).unmappedFields()
            .blockLoader(UnmappedFields.Filter.of(pattern::matches, pattern::objectSubfieldsCouldMatch), RESERVATION_FACTOR);
    }

    /**
     * Runs the block loader over a single document whose {@code _source} is {@code sourceJson} and returns the emitted
     * JSON parsed back into a map, or {@code null} if the loader emitted a null because no leaf matched the pattern.
     */
    private Map<String, Object> load(UnmappedFieldsPattern pattern, String sourceJson) throws IOException {
        BlockLoader loader = loader(pattern);
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(newLimitedBreaker(ByteSizeValue.ofMb(1)), null)) {
            BlockLoader.Builder builder = loader.builder(TestBlock.factory(), 1);
            reader.read(0, storedFields(Source.fromBytes(new BytesArray(sourceJson), XContentType.JSON)), builder);
            BytesRef json = (BytesRef) ((TestBlock) builder.build()).get(0);
            if (json == null) {
                return null;
            }
            return XContentHelper.convertToMap(new BytesArray(json.bytes, json.offset, json.length), false, XContentType.JSON).v2();
        }
    }

    /** Minimal stub; the loader only calls {@code source()}. */
    private static BlockLoader.StoredFields storedFields(Source source) {
        return new BlockLoader.StoredFields() {
            @Override
            public Source source() {
                return source;
            }

            @Override
            public String id() {
                return "0";
            }

            @Override
            public String routing() {
                return null;
            }

            @Override
            public Map<String, List<Object>> storedFields() {
                return Map.of();
            }

            @Override
            public boolean loaded() {
                return true;
            }
        };
    }
}
