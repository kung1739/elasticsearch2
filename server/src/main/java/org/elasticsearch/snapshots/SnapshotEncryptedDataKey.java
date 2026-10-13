/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.snapshots;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.core.Nullable;

import java.io.IOException;

/**
 * The {@code encrypted_data} of a create-snapshot request with its password replaced by key material derived from it, sealed by
 * {@link SnapshotGlobalStateTransformer#sealKey}. Stored on the in-progress snapshot so that any master can finalize it. The password
 * itself is never stored.
 */
public record SnapshotEncryptedDataKey(String type, @Nullable String passwordId, BytesReference sealedKey) implements Writeable {

    public SnapshotEncryptedDataKey(StreamInput in) throws IOException {
        this(in.readString(), in.readOptionalString(), in.readBytesReference());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(type);
        out.writeOptionalString(passwordId);
        out.writeBytesReference(sealedKey);
    }

    @Override
    public String toString() {
        return "SnapshotEncryptedDataKey{type=" + type + ", passwordId=" + passwordId + "}";
    }
}
