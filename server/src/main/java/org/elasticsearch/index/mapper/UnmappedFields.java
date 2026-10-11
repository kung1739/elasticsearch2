/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOFunction;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.IgnoredSourceFieldMapper.IgnoredSourceFormat;
import org.elasticsearch.index.mapper.blockloader.ConstantNull;
import org.elasticsearch.search.fetch.StoredFieldsSpec;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The fields of a shard's documents that its mapping does not declare. This is the one place that knows whether a shard can
 * hold such fields, what counts as one and how they are read.
 * <p>
 * An unmapped field is a leaf of {@code _source}: an object is walked down to the values below it and is never a value
 * itself, an array contributes each of its elements under the name of the array, and a name spelled with dots is the same
 * field as the path through the objects it names.
 */
public final class UnmappedFields {

    /**
     * Decides which unmapped fields a walk of {@code _source} is interested in.
     */
    public interface Filter {
        /** Whether a value at {@code path} is of interest. */
        boolean accepts(String path);

        /** Whether a value below {@code path} may be of interest. An object for which it is not is never entered. */
        boolean mayAcceptBelow(String path);

        static Filter of(Predicate<String> accepts, Predicate<String> mayAcceptBelow) {
            return new Filter() {
                @Override
                public boolean accepts(String path) {
                    return accepts.test(path);
                }

                @Override
                public boolean mayAcceptBelow(String path) {
                    return mayAcceptBelow.test(path);
                }
            };
        }
    }

    private final IndexSettings indexSettings;
    private final MappingLookup mappingLookup;
    @Nullable
    private final Predicate<String> existsInSegments;

    /**
     * @param existsInSegments whether a segment of the shard has a Lucene field of a given name, or {@code null} when the
     *                         segments cannot be inspected
     */
    public UnmappedFields(IndexSettings indexSettings, MappingLookup mappingLookup, @Nullable Predicate<String> existsInSegments) {
        this.indexSettings = indexSettings;
        this.mappingLookup = mappingLookup;
        this.existsInSegments = existsInSegments;
    }

    /**
     * Whether a document of this shard may hold, in its {@code _source}, a field that the mapping does not declare. When this
     * returns {@code false}, reading {@code _source} can find nothing beyond what the mapped fields already describe.
     * <p>
     * The answer is {@code false} in any of these cases:
     * <ul>
     *   <li>The index mode is strict columnar and unmapped fields are not routed to the flattened {@code _unmapped} field: such
     *       an index drops the fields its mapping does not declare while parsing the document.</li>
     *   <li>{@code _source} is synthetic and no segment has an {@code _ignored_source} field: a synthetic {@code _source} is
     *       rebuilt from the mapped fields plus {@code _ignored_source}, which is where an unmapped field is kept.</li>
     *   <li>{@code _source} is stored, the mapping never allowed an unmapped field to be kept (see
     *       {@link RootObjectMapper#retainsUnmappedFields()}) and no segment has an {@code _ignored} field, which records the
     *       dynamic fields that were skipped for exceeding a limit.</li>
     * </ul>
     * When the segments cannot be inspected, the answer is {@code true} unless the index mode alone decides.
     */
    public boolean mayExist() {
        if (indexSettings.getMode().isStrictColumnar() && indexSettings.isFlattenedUnmappedFieldsEnabled() == false) {
            return false;
        }
        if (existsInSegments == null) {
            return true;
        }
        if (mappingLookup.isSourceSynthetic()) {
            return existsInSegments.test(IgnoredSourceFieldMapper.NAME);
        }
        return mappingLookup.getMapping().getRoot().retainsUnmappedFields() || existsInSegments.test(IgnoredFieldMapper.NAME);
    }

    /**
     * Whether {@code name}, which this shard does not map, may be an unmapped field of one of its documents. It decides as
     * {@link #forEachLeaf} does, so that a field is found by name exactly where a walk finds it.
     */
    public boolean mayExist(String name) {
        return skipped("", name) == false && mayExist();
    }

    /**
     * Walks {@code source} and hands every unmapped leaf that {@code filter} is interested in to {@code consumer}, with its
     * dotted path and its value, in the order {@code source} holds them. A {@code null} is not a value, wherever it is.
     * <p>
     * A path that resolves to a field of this shard is not an unmapped field. Neither are the keys of a field whose type
     * takes an object as its value, such as a range: they are part of that value. A path below a field of any other type
     * is an unmapped field, as the field never read it.
     * <p>
     * A {@code nested} object is walked like any array of objects: a field its mapping does not declare is an unmapped
     * field that holds the values of all of the nested objects.
     */
    void forEachLeaf(Map<String, Object> source, Filter filter, BiConsumer<String, Object> consumer) {
        walkObject(source, "", filter, consumer);
    }

    /**
     * Whether {@code path} is that of a field whose type takes an object as its value, so that nothing at or below it is an
     * unmapped field.
     */
    private boolean skipped(String path) {
        return mappingLookup.getMapper(path) instanceof FieldMapper field && field.supportsParsingObject();
    }

    /**
     * Whether {@code key}, found in the object at {@code prefix}, is skipped along with everything below it. A key spelled
     * with dots is checked as the path through the objects it names.
     */
    private boolean skipped(String prefix, String key) {
        for (int dot = key.indexOf('.'); dot > 0; dot = key.indexOf('.', dot + 1)) {
            if (skipped(prefix + key.substring(0, dot))) {
                return true;
            }
        }
        return skipped(prefix + key);
    }

    private void walkObject(Map<String, Object> object, String prefix, Filter filter, BiConsumer<String, Object> consumer) {
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (skipped(prefix, entry.getKey())) {
                continue;
            }
            String path = prefix + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                walkBelow(child, path, filter, consumer);
            } else if (value instanceof List<?> elements) {
                walkArray(elements, path, filter, consumer);
            } else {
                leaf(path, value, filter, consumer);
            }
        }
    }

    private void walkArray(List<?> elements, String path, Filter filter, BiConsumer<String, Object> consumer) {
        for (Object element : elements) {
            if (element instanceof Map<?, ?> child) {
                walkBelow(child, path, filter, consumer);
            } else if (element instanceof List<?> nested) {
                walkArray(nested, path, filter, consumer);
            } else {
                leaf(path, element, filter, consumer);
            }
        }
    }

    @SuppressWarnings("unchecked") // an object of _source is keyed by field name
    private void walkBelow(Map<?, ?> object, String path, Filter filter, BiConsumer<String, Object> consumer) {
        if (filter.mayAcceptBelow(path)) {
            walkObject((Map<String, Object>) object, path + ".", filter, consumer);
        }
    }

    private void leaf(String path, @Nullable Object value, Filter filter, BiConsumer<String, Object> consumer) {
        // A name that resolves without a mapper of its own, such as a runtime field, is a field of the shard all the same.
        if (value != null && filter.accepts(path) && mappingLookup.getFieldType(path) == null) {
            consumer.accept(path, value);
        }
    }

    /**
     * Appends to {@code out} the keyword values that a value read from {@code _source} for an unmapped field stands for: an
     * object has none, an array has those of each of its elements, and anything else is one value, rendered as text.
     */
    public static void collectKeywords(@Nullable Object value, List<BytesRef> out) {
        if (value == null || value instanceof Map) {
            return;
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                collectKeywords(element, out);
            }
            return;
        }
        out.add(new BytesRef(value.toString()));
    }

    /**
     * Loads the unmapped field {@code name} as keywords. Loads nothing where {@link #mayExist(String)} rules it out.
     */
    public BlockLoader blockLoader(String name) {
        if (mayExist(name) == false) {
            return ConstantNull.INSTANCE;
        }
        // Naming the paths restricts the read to them; without any, the whole of _source is loaded.
        Set<String> sourcePaths = mappingLookup.isSourceEnabled() ? mappingLookup.sourcePaths(name) : Set.of();
        return new NamedFieldBlockLoader(name, sourcePaths, indexSettings.getIgnoredSourceFormat());
    }

    /**
     * Loads, for each document, the unmapped fields that {@code filter} is interested in as one JSON object, keyed by the
     * dotted path of each field and holding the value, or the array of values, {@code _source} has for it. A document without
     * any loads as {@code null}. Loads nothing where {@link #mayExist()} rules it out.
     *
     * @param sourceReservationFactor how many times the size of a serialized {@code _source} to reserve while it is parsed
     */
    public BlockLoader blockLoader(Filter filter, double sourceReservationFactor) {
        if (mayExist() == false) {
            return ConstantNull.INSTANCE;
        }
        return new AllFieldsBlockLoader(this, filter, sourceReservationFactor);
    }

    private abstract static class SourceBlockLoader implements BlockLoader {
        @Override
        public final Builder builder(BlockFactory factory, int expectedCount) {
            return factory.bytesRefs(expectedCount);
        }

        @Override
        public final IOFunction<CircuitBreaker, ColumnAtATimeReader> columnAtATimeReader(LeafReaderContext context) {
            return null;
        }

        @Override
        public final boolean supportsOrdinals() {
            return false;
        }

        @Override
        public final SortedSetDocValues ordinals(LeafReaderContext context) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NamedFieldBlockLoader extends SourceBlockLoader {
        private final String name;
        private final Set<String> sourcePaths;
        private final IgnoredSourceFormat ignoredSourceFormat;

        NamedFieldBlockLoader(String name, Set<String> sourcePaths, IgnoredSourceFormat ignoredSourceFormat) {
            this.name = name;
            this.sourcePaths = sourcePaths;
            this.ignoredSourceFormat = ignoredSourceFormat;
        }

        @Override
        public RowStrideReader rowStrideReader(CircuitBreaker breaker, LeafReaderContext context) {
            return new BlockStoredFieldsReader(breaker) {
                private final List<BytesRef> values = new ArrayList<>();

                @Override
                public void read(int docId, StoredFields storedFields, Builder builder) throws IOException {
                    values.clear();
                    collectKeywords(storedFields.source().extractValue(name, null), values);
                    appendKeywords(values, (BytesRefBuilder) builder);
                }

                @Override
                public String toString() {
                    return "UnmappedFields.Named.Reader[" + name + "]";
                }
            };
        }

        @Override
        public StoredFieldsSpec rowStrideStoredFieldSpec() {
            return StoredFieldsSpec.withSourcePaths(ignoredSourceFormat, sourcePaths);
        }

        @Override
        public String toString() {
            return "UnmappedFields.Named[" + name + "]";
        }
    }

    private static void appendKeywords(List<BytesRef> values, BlockLoader.BytesRefBuilder builder) {
        if (values.isEmpty()) {
            builder.appendNull();
        } else if (values.size() == 1) {
            builder.appendBytesRef(values.get(0));
        } else {
            builder.beginPositionEntry();
            for (BytesRef value : values) {
                builder.appendBytesRef(value);
            }
            builder.endPositionEntry();
        }
    }

    private static final class AllFieldsBlockLoader extends SourceBlockLoader {
        private final UnmappedFields unmappedFields;
        private final Filter filter;
        private final double sourceReservationFactor;

        AllFieldsBlockLoader(UnmappedFields unmappedFields, Filter filter, double sourceReservationFactor) {
            this.unmappedFields = unmappedFields;
            this.filter = filter;
            this.sourceReservationFactor = sourceReservationFactor;
        }

        @Override
        public RowStrideReader rowStrideReader(CircuitBreaker breaker, LeafReaderContext context) {
            return new BlockStoredFieldsReader(breaker) {
                private final Map<String, List<Object>> leaves = new LinkedHashMap<>();

                @Override
                public void read(int docId, StoredFields storedFields, Builder builder) throws IOException {
                    Source source = storedFields.source();
                    // Covers the parsed _source and the JSON built from it, both of which are proportional to its serialized size.
                    long reservation = (long) (source.internalSourceRef().length() * sourceReservationFactor);
                    breaker.addEstimateBytesAndMaybeBreak(reservation, "unmapped fields source");
                    try {
                        leaves.clear();
                        unmappedFields.forEachLeaf(
                            source.source(),
                            filter,
                            (path, value) -> leaves.computeIfAbsent(path, p -> new ArrayList<>(1)).add(value)
                        );
                        if (leaves.isEmpty()) {
                            builder.appendNull();
                            return;
                        }
                        try (XContentBuilder json = XContentFactory.jsonBuilder()) {
                            json.startObject();
                            for (Map.Entry<String, List<Object>> leaf : leaves.entrySet()) {
                                List<Object> values = leaf.getValue();
                                json.field(leaf.getKey(), values.size() == 1 ? values.get(0) : values);
                            }
                            json.endObject();
                            ((BytesRefBuilder) builder).appendBytesRef(BytesReference.bytes(json).toBytesRef());
                        }
                    } finally {
                        breaker.addWithoutBreaking(-reservation);
                    }
                }

                @Override
                public String toString() {
                    return "UnmappedFields.All.Reader";
                }
            };
        }

        @Override
        public StoredFieldsSpec rowStrideStoredFieldSpec() {
            return new StoredFieldsSpec(true, false, Set.of());
        }

        @Override
        public String toString() {
            return "UnmappedFields.All";
        }
    }
}
