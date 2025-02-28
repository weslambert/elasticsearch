/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest;

import org.elasticsearch.common.util.Maps;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.script.Metadata;

import java.time.ZonedDateTime;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Map containing ingest source and metadata.
 *
 * The Metadata values in {@link IngestDocument.Metadata} are validated when put in the map.
 * _index, _id and _routing must be a String or null
 * _version_type must be a lower case VersionType or null
 * _version must be representable as a long without loss of precision or null
 * _dyanmic_templates must be a map
 * _if_seq_no must be a long or null
 * _if_primary_term must be a long or null
 *
 * The map is expected to be used by processors, server code should the typed getter and setters where possible.
 */
class IngestSourceAndMetadata extends AbstractMap<String, Object> {

    protected final Map<String, Object> source;
    protected final Metadata metadata;

    /**
     * Create an IngestSourceAndMetadata with the given metadata, source and default validators
     */
    IngestSourceAndMetadata(
        String index,
        String id,
        long version,
        String routing,
        VersionType versionType,
        ZonedDateTime timestamp,
        Map<String, Object> source
    ) {
        this(new HashMap<>(source), new Metadata(index, id, version, routing, versionType, timestamp));
    }

    /**
     * Create IngestSourceAndMetadata from a source and metadata
     *
     * @param source the source document map
     * @param metadata the metadata map
     */
    IngestSourceAndMetadata(Map<String, Object> source, Metadata metadata) {
        this.source = source != null ? source : new HashMap<>();
        this.metadata = metadata;
        Set<String> badKeys = Sets.intersection(this.metadata.keySet(), this.source.keySet());
        if (badKeys.size() > 0) {
            throw new IllegalArgumentException(
                "unexpected metadata ["
                    + badKeys.stream().sorted().map(k -> k + ":" + this.source.get(k)).collect(Collectors.joining(", "))
                    + "] in source"
            );
        }
    }

    /**
     * Returns a new metadata map and the existing source map with metadata removed.
     */
    public static Tuple<Map<String, Object>, Map<String, Object>> splitSourceAndMetadata(Map<String, Object> sourceAndMetadata) {
        if (sourceAndMetadata instanceof IngestSourceAndMetadata ingestSourceAndMetadata) {
            return new Tuple<>(new HashMap<>(ingestSourceAndMetadata.source), new HashMap<>(ingestSourceAndMetadata.metadata.getMap()));
        }
        Map<String, Object> metadata = Maps.newHashMapWithExpectedSize(IngestDocument.Metadata.values().length);
        Map<String, Object> source = new HashMap<>(sourceAndMetadata);
        for (IngestDocument.Metadata ingestDocumentMetadata : IngestDocument.Metadata.values()) {
            String metadataName = ingestDocumentMetadata.getFieldName();
            if (sourceAndMetadata.containsKey(metadataName)) {
                metadata.put(metadataName, source.remove(metadataName));
            }
        }
        return new Tuple<>(source, metadata);
    }

    /**
     * Fetch the timestamp from the ingestMetadata, if it exists
     * @return the timestamp for the document or null
     */
    public static ZonedDateTime getTimestamp(Map<String, Object> ingestMetadata) {
        if (ingestMetadata == null) {
            return null;
        }
        Object ts = ingestMetadata.get(IngestDocument.TIMESTAMP);
        if (ts instanceof ZonedDateTime timestamp) {
            return timestamp;
        } else if (ts instanceof String str) {
            return ZonedDateTime.parse(str);
        }
        return null;
    }

    /**
     * get the source map, if externally modified then the guarantees of this class are not enforced
     */
    public Map<String, Object> getSource() {
        return source;
    }

    /**
     * get the metadata map, if externally modified then the guarantees of this class are not enforced
     */
    public Metadata getMetadata() {
        return metadata;
    }

    /**
     * Returns an entrySet that respects the validators of the map.
     */
    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        // Make a copy of the Metadata.keySet() to avoid a ConcurrentModificationException when removing a value from the iterator
        return new EntrySet(source.entrySet(), new HashSet<>(metadata.keySet()));
    }

    /**
     * Associate a key with a value.  If the key has a validator, it is applied before association.
     * @throws IllegalArgumentException if value does not pass validation for the given key
     */
    @Override
    public Object put(String key, Object value) {
        if (metadata.isAvailable(key)) {
            return metadata.put(key, value);
        }
        return source.put(key, value);
    }

    /**
     * Remove the mapping of key.  If the key has a validator, it is checked before key removal.
     * @throws IllegalArgumentException if the validator does not allow the key to be removed
     */
    @Override
    public Object remove(Object key) {
        // uses map directly to avoid AbstractMaps linear time implementation using entrySet()
        if (key instanceof String str) {
            if (metadata.isAvailable(str)) {
                return metadata.remove(str);
            }
        }
        return source.remove(key);
    }

    /**
     * Clear entire map.  For each key in the map with a validator, that validator is checked as in {@link #remove(Object)}.
     * @throws IllegalArgumentException if any validator does not allow the key to be removed, in this case the map is unmodified
     */
    @Override
    public void clear() {
        // AbstractMap uses entrySet().clear(), it should be quicker to run through the validators, then call the wrapped maps clear
        for (String key : metadata.keySet()) {
            metadata.remove(key);
        }
        source.clear();
    }

    @Override
    public int size() {
        // uses map directly to avoid creating an EntrySet via AbstractMaps implementation, which returns entrySet().size()
        return source.size() + metadata.size();
    }

    @Override
    public boolean containsValue(Object value) {
        // uses map directly to avoid AbstractMaps linear time implementation using entrySet()
        return metadata.containsValue(value) || source.containsValue(value);
    }

    @Override
    public boolean containsKey(Object key) {
        // uses map directly to avoid AbstractMaps linear time implementation using entrySet()
        if (key instanceof String str) {
            return metadata.containsKey(str) || source.containsKey(key);
        }
        return source.containsKey(key);
    }

    @Override
    public Object get(Object key) {
        // uses map directly to avoid AbstractMaps linear time implementation using entrySet()
        if (key instanceof String str) {
            if (metadata.isAvailable(str)) {
                return metadata.get(str);
            }
        }
        return source.get(key);
    }

    /**
     * Set of entries of the wrapped map that calls the appropriate validator before changing an entries value or removing an entry.
     *
     * Inherits {@link AbstractSet#removeAll(Collection)}, which calls the overridden {@link #remove(Object)} which performs validation.
     *
     * Inherits {@link AbstractCollection#retainAll(Collection)} and {@link AbstractCollection#clear()}, which both use
     * {@link EntrySetIterator#remove()} for removal.
     */
    class EntrySet extends AbstractSet<Map.Entry<String, Object>> {
        Set<Map.Entry<String, Object>> sourceSet;
        Set<String> metadataKeys;

        EntrySet(Set<Map.Entry<String, Object>> sourceSet, Set<String> metadataKeys) {
            this.sourceSet = sourceSet;
            this.metadataKeys = metadataKeys;
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
            return new EntrySetIterator(sourceSet.iterator(), metadataKeys.iterator());
        }

        @Override
        public int size() {
            return sourceSet.size() + metadataKeys.size();
        }

        @Override
        public boolean remove(Object o) {
            if (o instanceof Map.Entry<?, ?> entry) {
                if (entry.getKey()instanceof String key) {
                    if (metadata.containsKey(key)) {
                        if (Objects.equals(entry.getValue(), metadata.get(key))) {
                            metadata.remove(key);
                            return true;
                        }
                    }
                }
            }
            return sourceSet.remove(o);
        }
    }

    /**
     * Iterator over the wrapped map that returns a validating {@link Entry} on {@link #next()} and validates on {@link #remove()}.
     *
     * {@link #remove()} is called by remove in {@link AbstractMap#values()}, {@link AbstractMap#keySet()}, {@link AbstractMap#clear()} via
     * {@link AbstractSet#clear()}
     */
    class EntrySetIterator implements Iterator<Map.Entry<String, Object>> {
        final Iterator<Map.Entry<String, Object>> sourceIter;
        final Iterator<String> metadataKeyIter;

        boolean sourceCur = true;
        Map.Entry<String, Object> cur;

        EntrySetIterator(Iterator<Map.Entry<String, Object>> sourceIter, Iterator<String> metadataKeyIter) {
            this.sourceIter = sourceIter;
            this.metadataKeyIter = metadataKeyIter;
        }

        @Override
        public boolean hasNext() {
            return sourceIter.hasNext() || metadataKeyIter.hasNext();
        }

        @Override
        public Map.Entry<String, Object> next() {
            sourceCur = sourceIter.hasNext();
            return cur = sourceCur ? sourceIter.next() : new Entry(metadataKeyIter.next());
        }

        /**
         * Remove current entry from the backing Map.  Checks the Entry's key's validator, if one exists, before removal.
         * @throws IllegalArgumentException if the validator does not allow the Entry to be removed
         * @throws IllegalStateException if remove is called before {@link #next()}
         */
        @Override
        public void remove() {
            if (cur == null) {
                throw new IllegalStateException();
            }
            if (sourceCur) {
                sourceIter.remove();
            } else {
                metadata.remove(cur.getKey());
            }
        }
    }

    /**
     * Map.Entry that stores metadata key and calls into {@link #metadata} for {@link #setValue}
     */
    class Entry implements Map.Entry<String, Object> {
        final String key;

        Entry(String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return metadata.get(key);
        }

        @Override
        public Object setValue(Object value) {
            return metadata.put(key, value);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (super.equals(o) == false) return false;
        IngestSourceAndMetadata that = (IngestSourceAndMetadata) o;
        return Objects.equals(source, that.source) && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, metadata);
    }
}
