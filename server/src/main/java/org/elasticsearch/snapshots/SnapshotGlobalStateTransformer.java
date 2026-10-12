/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.snapshots;

import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.core.Nullable;

/**
 * SPI hook that lets a plugin transform the global metadata written into a snapshot, e.g. to re-wrap or drop encrypted project data.
 * At most one implementation may be installed.
 */
public interface SnapshotGlobalStateTransformer {

    SnapshotGlobalStateTransformer NOOP = new SnapshotGlobalStateTransformer() {};

    /**
     * Derives key material from a snapshot {@code password} and seals it so that it can be stored in cluster state and later passed to
     * {@link #transformForSnapshot} by whichever node is master at finalization. May be expensive; must not be called on a transport
     * thread.
     */
    default BytesReference sealKey(ProjectId projectId, SecureString password) {
        throw new IllegalArgumentException("[encrypted_data] is not supported by this cluster");
    }

    /**
     * Called on the SNAPSHOT thread pool during finalization, before the global-metadata blob is written.
     *
     * @param sealedKey the output of {@link #sealKey} for this snapshot, or {@code null} if no {@code encrypted_data} was requested, in
     *                  which case encrypted data must be excluded
     * @return the metadata to write, or the same instance if unchanged
     */
    default Metadata transformForSnapshot(ProjectId projectId, Metadata metadata, @Nullable BytesReference sealedKey) {
        return metadata;
    }

    /**
     * Returns {@code true} if the project contains data this transformer would re-wrap or exclude.
     */
    default boolean containsEncryptedData(ProjectId projectId, Metadata metadata) {
        return false;
    }
}
