/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.mapper.blockloader.ConstantNull;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests that {@link UnmappedFields#blockLoader(String)} reads a {@code _source} value as the keywords of an unmapped field: an
 * object value is read as {@code null}, an array contributes its scalar elements, and a scalar becomes a single value.
 */
public class UnmappedFieldsTests extends MapperServiceTestCase {

    public void testScalarReadsAsSingleValue() throws IOException {
        assertThat(load("f", Map.of("f", "hello")), equalTo("hello"));
    }

    public void testNonStringScalarsUseToString() throws IOException {
        assertThat(load("f", Map.of("f", 42)), equalTo("42"));
        assertThat(load("f", Map.of("f", true)), equalTo("true"));
        assertThat(load("f", Map.of("f", 1.5)), equalTo("1.5"));
    }

    public void testObjectReadsAsNull() throws IOException {
        assertThat(load("f", Map.of("f", Map.of("a", "b"))), nullValue());
    }

    public void testArrayOfScalarsReadsAsMultivalue() throws IOException {
        assertThat(load("f", Map.of("f", List.of("a", "b"))), equalTo(List.of("a", "b")));
    }

    public void testNestedArraysAreFlattened() throws IOException {
        assertThat(load("f", Map.of("f", List.of(List.of("a", "b"), "c"))), equalTo(List.of("a", "b", "c")));
    }

    public void testObjectsInsideArrayAreDropped() throws IOException {
        assertThat(load("f", Map.of("f", Arrays.asList("a", Map.of("k", "v"), "b"))), equalTo(List.of("a", "b")));
    }

    public void testArrayOfOnlyObjectsReadsAsNull() throws IOException {
        assertThat(load("f", Map.of("f", List.of(Map.of("a", "b"), Map.of("c", "d")))), nullValue());
    }

    public void testEmptyArrayReadsAsNull() throws IOException {
        assertThat(load("f", Map.of("f", List.of())), nullValue());
    }

    public void testMissingFieldReadsAsNull() throws IOException {
        assertThat(load("f", Map.of("other", "x")), nullValue());
    }

    public void testExplicitNullReadsAsNull() throws IOException {
        Map<String, Object> source = new HashMap<>();
        source.put("f", null);
        assertThat(load("f", source), nullValue());
    }

    public void testSingleElementArrayCollapsesToSingleValue() throws IOException {
        assertThat(load("f", Map.of("f", List.of("only"))), equalTo("only"));
    }

    public void testDottedPathIsExtracted() throws IOException {
        assertThat(load("a.b", Map.of("a", Map.of("b", "leaf"))), equalTo("leaf"));
    }

    public void testDottedPathThroughArrayOfObjects() throws IOException {
        assertThat(load("a.b", Map.of("a", List.of(Map.of("b", "x"), Map.of("b", "y")))), equalTo(List.of("x", "y")));
    }

    public void testValuesAreUtf8Encoded() throws IOException {
        assertThat(load("f", Map.of("f", "χαίρετε")), equalTo("χαίρετε"));
    }

    public void testScalarsFromParsedJson() throws IOException {
        Source source = Source.fromBytes(new BytesArray("""
            {"f": 1.5}"""), XContentType.JSON);
        assertThat(loadFrom("f", source), equalTo("1.5"));
    }

    public void testDoesNotSupportOrdinals() throws IOException {
        BlockLoader loader = loader("f");
        assertThat(loader.supportsOrdinals(), equalTo(false));
        expectThrows(UnsupportedOperationException.class, () -> loader.ordinals(null));
    }

    public void testNoColumnAtATimeReader() throws IOException {
        assertThat(loader("f").columnAtATimeReader(null), nullValue());
    }

    public void testToStringNamesTheFieldAndIsDistinctFromTheReader() throws IOException {
        BlockLoader loader = loader("some_field");
        assertThat(loader.toString(), equalTo("UnmappedFields.Named[some_field]"));
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(newLimitedBreaker(ByteSizeValue.ofMb(1)), null)) {
            assertThat(reader.toString(), equalTo("UnmappedFields.Named.Reader[some_field]"));
        }
    }

    /**
     * One reader serves a whole page ({@link org.elasticsearch.index.mapper.BlockStoredFieldsReader#canReuse} is always true), so the
     * scratch list it collects into has to be reset between documents. Interleaving multivalue, single, null and multivalue catches
     * values bleeding from one position into the next.
     */
    public void testOneReaderAcrossManyDocuments() throws IOException {
        List<Map<String, Object>> docs = List.of(
            Map.of("f", List.of("a", "b")),
            Map.of("f", "c"),
            Map.of("other", "x"),
            Map.of("f", List.of("d", "e"))
        );
        BlockLoader loader = loader("f");
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(newLimitedBreaker(ByteSizeValue.ofMb(1)), null)) {
            BlockLoader.Builder builder = loader.builder(TestBlock.factory(), docs.size());
            for (int doc = 0; doc < docs.size(); doc++) {
                reader.read(doc, storedFields(Source.fromMap(docs.get(doc), XContentType.JSON)), builder);
            }
            TestBlock block = (TestBlock) builder.build();
            assertThat(asValue(block.get(0)), equalTo(List.of("a", "b")));
            assertThat(asValue(block.get(1)), equalTo("c"));
            assertThat(asValue(block.get(2)), nullValue());
            assertThat(asValue(block.get(3)), equalTo(List.of("d", "e")));
        }
    }

    /**
     * This reader adds no accounting of its own on top of the fixed reservation it inherits, so reading must not move the breaker.
     * Pins that asymmetry, so a per-document reservation added later without a matching release is caught.
     */
    public void testReadingHoldsOnlyTheInheritedReservation() throws IOException {
        CircuitBreaker breaker = newLimitedBreaker(ByteSizeValue.ofMb(1));
        BlockLoader loader = loader("f");
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(breaker, null)) {
            assertThat(breaker.getUsed(), equalTo(BlockSourceReader.ESTIMATED_SIZE));
            BlockLoader.Builder builder = loader.builder(TestBlock.factory(), 1);
            reader.read(0, storedFields(Source.fromMap(Map.of("f", "x".repeat(4096)), XContentType.JSON)), builder);
            assertThat(breaker.getUsed(), equalTo(BlockSourceReader.ESTIMATED_SIZE));
        }
        assertThat(breaker.getUsed(), equalTo(0L));
    }

    /**
     * A nested object is walked like any array of objects: the fields its mapping declares are left out and the others
     * hold the values of all of the nested objects, at any depth and by name too.
     */
    public void testUndeclaredFieldsBelowANestedObject() throws IOException {
        MapperService mapperService = createMapperService(topMapping(b -> {
            b.field("dynamic", false);
            b.startObject("properties");
            b.startObject("item").field("type", "nested").startObject("properties");
            b.startObject("declared").field("type", "keyword").endObject();
            b.startObject("inner").field("type", "nested").endObject();
            b.endObject().endObject();
            b.endObject();
        }));
        String json = """
            {
              "item": [ { "value": "a", "declared": "b", "obj": { "value": "c" }, "inner": [ { "value": "d" } ] }, { "value": "e" } ],
              "item.value": "f",
              "item.declared": "g"
            }""";
        assertThat(
            leaves(mapperService, json),
            equalTo(Map.of("item.value", List.of("a", "e", "f"), "item.obj.value", List.of("c"), "item.inner.value", List.of("d")))
        );
        UnmappedFields unmappedFields = createSearchExecutionContext(mapperService).unmappedFields();
        for (String name : List.of("item.value", "item.obj.value", "item.inner.value")) {
            assertTrue(name, unmappedFields.mayExist(name));
            assertThat(name, unmappedFields.blockLoader(name), hasToString("UnmappedFields.Named[" + name + "]"));
        }
        BlockLoader loader = unmappedFields.blockLoader("item.value");
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(newLimitedBreaker(ByteSizeValue.ofMb(1)), null)) {
            BlockLoader.Builder builder = loader.builder(TestBlock.factory(), 1);
            reader.read(0, storedFields(Source.fromBytes(new BytesArray(json), XContentType.JSON)), builder);
            assertThat((List<?>) asValue(((TestBlock) builder.build()).get(0)), containsInAnyOrder("a", "e", "f"));
        }
    }

    /**
     * A key of the value of a mapped field is not an unmapped field, for the walk and by name. That holds for the types
     * that take an object as their value only.
     */
    public void testNameBelowAMappedFieldIsNotAnUnmappedField() throws IOException {
        MapperService mapperService = createMapperService(topMapping(b -> {
            b.field("dynamic", false);
            b.startObject("properties");
            b.startObject("span").field("type", "integer_range").endObject();
            b.startObject("labels").field("type", "flattened").endObject();
            b.startObject("obj").startObject("properties").startObject("point").field("type", "geo_point").endObject().endObject();
            b.endObject();
            b.endObject();
        }));
        UnmappedFields unmappedFields = createSearchExecutionContext(mapperService).unmappedFields();
        for (String name : List.of("span.gte", "labels.env", "labels.a.b", "obj.point.lat")) {
            assertFalse(name, unmappedFields.mayExist(name));
            assertThat(name, unmappedFields.blockLoader(name), sameInstance(ConstantNull.INSTANCE));
        }
        for (String name : List.of("spans.gte", "obj.other", "obj.points.lat")) {
            assertTrue(name, unmappedFields.mayExist(name));
        }
        assertThat(leaves(mapperService, """
            {
              "span": { "gte": 1 }, "labels": { "env": "prod" }, "obj": { "point": { "lat": 1, "lon": 2 }, "other": 3 },
              "spans": { "gte": 4 }
            }"""), equalTo(Map.of("obj.other", List.of(3), "spans.gte", List.of(4))));
    }

    /**
     * Whatever resolves to a field of the shard is left out: a field, its multi-fields, an alias and a runtime field.
     */
    public void testNamesThatResolveToAFieldAreLeftOut() throws IOException {
        MapperService mapperService = createMapperService(topMapping(b -> {
            b.field("dynamic", false);
            b.startObject("runtime").startObject("computed").field("type", "keyword").endObject().endObject();
            b.startObject("properties");
            b.startObject("field").field("type", "keyword");
            b.startObject("fields").startObject("raw").field("type", "keyword").endObject().endObject();
            b.endObject();
            b.startObject("alias").field("type", "alias").field("path", "field").endObject();
            b.endObject();
        }));
        assertThat(leaves(mapperService, """
            { "field": "a", "field.raw": "b", "alias": "c", "computed": "d", "other": "e" }"""), equalTo(Map.of("other", List.of("e"))));
    }

    /**
     * A field whose type does not take an object never read what is below its path, so that is an unmapped field. It is
     * how a document spells a field next to it where subobjects are disabled, and what a document stored before the field
     * was mapped.
     */
    public void testPathsBelowAFieldThatTakesNoObject() throws IOException {
        MapperService root = createMapperService(topMapping(b -> {
            b.field("dynamic", false).field("subobjects", false);
            b.startObject("properties").startObject("host").field("type", "keyword").endObject().endObject();
        }));
        assertThat(
            leaves(root, """
                { "host": "h", "host.name": "n", "other.name": "o" }"""),
            equalTo(Map.of("host.name", List.of("n"), "other.name", List.of("o")))
        );
        assertThat(
            createSearchExecutionContext(root).unmappedFields().blockLoader("host.name"),
            hasToString("UnmappedFields.Named[host.name]")
        );

        MapperService inner = createMapperService(topMapping(b -> {
            b.field("dynamic", false);
            b.startObject("properties");
            b.startObject("attributes").field("type", "object").field("subobjects", false).field("dynamic", false);
            b.startObject("properties").startObject("host").field("type", "keyword").endObject().endObject();
            b.endObject();
            b.startObject("plain").field("type", "keyword").endObject();
            b.endObject();
        }));
        assertThat(
            leaves(inner, """
                { "attributes": { "host": "h", "host.name": "n" }, "attributes.host.os": "o", "plain": { "below": "p" } }"""),
            equalTo(Map.of("attributes.host.name", List.of("n"), "attributes.host.os", List.of("o"), "plain.below", List.of("p")))
        );
        UnmappedFields unmappedFields = createSearchExecutionContext(inner).unmappedFields();
        assertTrue(unmappedFields.mayExist("plain.below"));
        assertThat(unmappedFields.blockLoader("plain.below"), hasToString("UnmappedFields.Named[plain.below]"));
    }

    /** The unmapped leaves of a document, by path. */
    private Map<String, List<Object>> leaves(MapperService mapperService, String json) {
        Map<String, Object> source = XContentHelper.convertToMap(JsonXContent.jsonXContent, json, true);
        Map<String, List<Object>> leaves = new LinkedHashMap<>();
        createSearchExecutionContext(mapperService).unmappedFields()
            .forEachLeaf(
                source,
                UnmappedFields.Filter.of(path -> true, path -> true),
                (path, value) -> leaves.computeIfAbsent(path, p -> new ArrayList<>()).add(value)
            );
        return leaves;
    }

    /**
     * The loader for a field of a shard whose mapping keeps the fields it does not declare.
     */
    private BlockLoader loader(String fieldName) throws IOException {
        MapperService mapperService = createMapperService(topMapping(b -> b.field("dynamic", false)));
        return createSearchExecutionContext(mapperService).unmappedFields().blockLoader(fieldName);
    }

    /**
     * Runs the loader over a single document and returns what landed in the block: {@code null}, a {@code String} for a single value,
     * or a {@code List<String>} for a multivalue. {@link TestBlock} stores a position entry as a nested list, so the shape of the
     * return value is itself part of what these tests assert.
     */
    private Object load(String fieldName, Map<String, Object> sourceMap) throws IOException {
        return loadFrom(fieldName, Source.fromMap(sourceMap, XContentType.JSON));
    }

    private Object loadFrom(String fieldName, Source source) throws IOException {
        BlockLoader loader = loader(fieldName);
        try (BlockLoader.RowStrideReader reader = loader.rowStrideReader(newLimitedBreaker(ByteSizeValue.ofMb(1)), null)) {
            BlockLoader.Builder builder = loader.builder(TestBlock.factory(), 1);
            reader.read(0, storedFields(source), builder);
            return asValue(((TestBlock) builder.build()).get(0));
        }
    }

    /** {@link TestBlock} stores a position entry as a nested list, so the shape here is itself part of what the tests assert. */
    private static Object asValue(Object blockValue) {
        if (blockValue instanceof List<?> list) {
            return list.stream().map(v -> ((BytesRef) v).utf8ToString()).toList();
        }
        return blockValue == null ? null : ((BytesRef) blockValue).utf8ToString();
    }

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
