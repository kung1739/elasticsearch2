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
import java.util.function.Predicate;

/**
 * The fields of a shard's documents that its mapping does not declare. This is the one place that knows whether a shard can
 * hold such fields, where they are read from and what counts as one, so that whatever looks for them agrees on the answer.
 * <p>
 * An unmapped field is a leaf of {@code _source}: an object is walked down to the values below it and is never a value
 * itself, an array contributes each of its elements under the name of the array, and a name spelled with dots is the same
 * field as the path through the objects it names.
 */
public final class UnmappedFields {

    /**
     * Decides which leaves a walk of {@code _source} is interested in. It is consulted while descending, one key at a time, so
     * that a subtree holding nothing of interest is never entered. A filter carries what it has learnt about the path so far
     * in a state, which it is handed back along with the path itself; an implementation may rely on either.
     */
    public interface Filter {
        /** The state from which neither the path reached nor any path below it is of interest. */
        int REJECT = -1;

        /** The state before any key has been seen. */
        int start();

        /**
         * The state after descending from {@code state} into {@code key}, which completes {@code path}.
         */
        int key(int state, String path, String key);

        /**
         * The state for the keys of an object found at {@code path}, which was reached in {@code state}.
         */
        int below(int state, String path);

        /**
         * Whether a value found at {@code path}, which was reached in {@code state}, is of interest.
         */
        boolean accepts(int state, String path);

        /**
         * A filter that decides on the path alone.
         *
         * @param accepts        whether a value at a path is of interest
         * @param mayAcceptBelow whether a value at some path below a path may be of interest
         */
        static Filter of(Predicate<String> accepts, Predicate<String> mayAcceptBelow) {
            return new Filter() {
                @Override
                public int start() {
                    return 0;
                }

                @Override
                public int key(int state, String path, String key) {
                    return accepts.test(path) || mayAcceptBelow.test(path) ? 0 : REJECT;
                }

                @Override
                public int below(int state, String path) {
                    return mayAcceptBelow.test(path) ? 0 : REJECT;
                }

                @Override
                public boolean accepts(int state, String path) {
                    return accepts.test(path);
                }
            };
        }
    }

    /**
     * Receives the leaves a walk of {@code _source} finds.
     */
    @FunctionalInterface
    public interface LeafConsumer {
        /**
         * @param path  the dotted path of the leaf
         * @param value the value as {@code _source} holds it, {@code null} for a {@code null} element of an array
         */
        void accept(String path, @Nullable Object value);
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
     * <p>
     * A value a mapped field keeps under its own name, such as the keys of an object-valued field, is not an unmapped field
     * and is not covered by this method. See {@link #mayExist(String)}.
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
     * Whether reading {@code name} from {@code _source} can return a value on this shard, given that the shard does not map
     * it. That takes either a shard that {@link #mayExist() may hold unmapped fields}, or one that maps a field above
     * {@code name}: a mapped field keeps its whole value under its own name, so the keys of an object-valued one are read
     * from {@code _source} without being fields of their own.
     */
    public boolean mayExist(String name) {
        if (mayExist()) {
            return true;
        }
        for (int dot = name.lastIndexOf('.'); dot > 0; dot = name.lastIndexOf('.', dot - 1)) {
            if (mappingLookup.getFieldType(name.substring(0, dot)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks {@code source} and hands every unmapped leaf that {@code filter} is interested in to {@code consumer}, in the
     * order {@code _source} holds them.
     * <p>
     * Nothing at or below a path this shard maps is an unmapped field: a mapped field keeps its whole value under its own
     * name, whatever the shape of that value.
     * <p>
     * A {@code nested} object is walked like any array of objects, so a field its mapping does not declare is read from
     * the root document as the values all of its nested objects hold for it.
     */
    public void forEachLeaf(Map<String, Object> source, Filter filter, LeafConsumer consumer) {
        forEachLeaf(source, filter, path -> mappingLookup.getMapper(path) != null, consumer);
    }

    /**
     * Walks {@code source} and hands every leaf that {@code filter} is interested in to {@code consumer}, in the order
     * {@code source} holds them.
     *
     * @param skipped the paths that are left out along with everything below them
     */
    public static void forEachLeaf(Map<String, Object> source, Filter filter, Predicate<String> skipped, LeafConsumer consumer) {
        walkObject(source, "", filter.start(), filter, skipped, consumer);
    }

    private static void walkObject(
        Map<String, Object> object,
        String prefix,
        int state,
        Filter filter,
        Predicate<String> skipped,
        LeafConsumer consumer
    ) {
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            String path = prefix + entry.getKey();
            if (skipped.test(path)) {
                continue;
            }
            int keyState = filter.key(state, path, entry.getKey());
            if (keyState == Filter.REJECT) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                walkBelow(child, path, keyState, filter, skipped, consumer);
            } else if (value instanceof List<?> elements) {
                walkArray(elements, path, keyState, filter, skipped, consumer);
            } else if (value != null && filter.accepts(keyState, path)) {
                consumer.accept(path, value);
            }
        }
    }

    private static void walkArray(
        List<?> elements,
        String path,
        int state,
        Filter filter,
        Predicate<String> skipped,
        LeafConsumer consumer
    ) {
        for (Object element : elements) {
            if (element instanceof Map<?, ?> child) {
                walkBelow(child, path, state, filter, skipped, consumer);
            } else if (element instanceof List<?> nested) {
                walkArray(nested, path, state, filter, skipped, consumer);
            } else if (filter.accepts(state, path)) {
                consumer.accept(path, element);
            }
        }
    }

    @SuppressWarnings("unchecked") // an object of _source is keyed by field name
    private static void walkBelow(
        Map<?, ?> object,
        String path,
        int state,
        Filter filter,
        Predicate<String> skipped,
        LeafConsumer consumer
    ) {
        int below = filter.below(state, path);
        if (below != Filter.REJECT) {
            walkObject((Map<String, Object>) object, path + ".", below, filter, skipped, consumer);
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
                        unmappedFields.forEachLeaf(source.source(), filter, (path, value) -> {
                            // A null says nothing about its field, like the nulls a mapped field does not keep.
                            if (value != null) {
                                leaves.computeIfAbsent(path, p -> new ArrayList<>(1)).add(value);
                            }
                        });
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
