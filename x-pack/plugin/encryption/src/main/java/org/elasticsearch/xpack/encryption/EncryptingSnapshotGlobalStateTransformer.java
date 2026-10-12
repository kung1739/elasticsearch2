/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.encryption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.snapshots.SnapshotGlobalStateTransformer;
import org.elasticsearch.xpack.encryption.spi.EncryptedData;
import org.elasticsearch.xpack.encryption.spi.EncryptedDataHandler;
import org.elasticsearch.xpack.encryption.spi.EncryptionService;
import org.elasticsearch.xpack.encryption.spi.EncryptionServiceRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.function.UnaryOperator;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.elasticsearch.xpack.encryption.PasswordBasedEncryption.KEK_LENGTH_BITS;
import static org.elasticsearch.xpack.encryption.PasswordBasedEncryption.SALT_LENGTH_BYTES;

/**
 * Transforms snapshot global state by re-wrapping PEK-encrypted project customs under a
 * password-derived key. Registered as a {@link SnapshotGlobalStateTransformer} via
 * {@code META-INF/services}.
 *
 * <p>{@link #sealKey} derives a key-encryption-key (KEK) from the snapshot password via PBKDF2 and
 * encrypts {@code salt | KEK} under the PEK, so the password itself is never stored. At finalization,
 * {@link #transformForSnapshot} unseals the KEK and, for each handler whose custom context includes
 * {@link Metadata.XContentContext#SNAPSHOT}, decrypts each value with the PEK and re-wraps it under the KEK.
 *
 * <p>Without a sealed key every such value is dropped so the snapshot never contains PEK-wrapped data.
 *
 * <p>Customs whose context does not include {@link Metadata.XContentContext#SNAPSHOT} are left
 * untouched — the repository writer will not serialise them at all.
 */
public final class EncryptingSnapshotGlobalStateTransformer implements SnapshotGlobalStateTransformer {

    private static final Logger logger = LogManager.getLogger(EncryptingSnapshotGlobalStateTransformer.class);

    /** Key ID written into every {@link EncryptedData} value that is re-wrapped under a snapshot password. */
    static final String SNAPSHOT_PASSWORD_KEY_ID = "snapshot_password";

    private static final int KEK_LENGTH_BYTES = KEK_LENGTH_BITS / Byte.SIZE;

    @Override
    public BytesReference sealKey(ProjectId projectId, SecureString password) {
        final char[] chars = password.getChars().clone();
        final byte[] keyMaterial = new byte[SALT_LENGTH_BYTES + KEK_LENGTH_BYTES];
        try {
            final byte[] salt = PasswordBasedEncryption.generateSalt();
            final byte[] kek = PasswordBasedEncryption.deriveKek(chars, salt).getEncoded();
            System.arraycopy(salt, 0, keyMaterial, 0, SALT_LENGTH_BYTES);
            System.arraycopy(kek, 0, keyMaterial, SALT_LENGTH_BYTES, KEK_LENGTH_BYTES);
            Arrays.fill(kek, (byte) 0);
            final EncryptedData sealed = EncryptionServiceRegistry.getEncryptionService().encrypt(keyMaterial);
            final BytesStreamOutput out = new BytesStreamOutput();
            sealed.writeTo(out);
            return out.bytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Arrays.fill(chars, '\0');
            Arrays.fill(keyMaterial, (byte) 0);
        }
    }

    /**
     * Returns the ID of the project encryption key that {@code sealedKey} is encrypted under. That key must not be retired while a
     * snapshot still references it.
     */
    static String sealedKeyId(BytesReference sealedKey) {
        return readSealedKey(sealedKey).keyId();
    }

    private static EncryptedData readSealedKey(BytesReference sealedKey) {
        try (StreamInput in = sealedKey.streamInput()) {
            return new EncryptedData(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Metadata transformForSnapshot(ProjectId projectId, Metadata metadata, @Nullable BytesReference sealedKey) {
        EncryptedDataHandlerRegistry registry = EncryptedDataHandlerRegistry.getInstance();
        if (registry == null || registry.handlers().isEmpty()) {
            return metadata;
        }

        final ProjectMetadata project = metadata.getProject(projectId);
        if (project == null) {
            return metadata;
        }

        // Skip customs that are not in SNAPSHOT context — the repository layer won't write them anyway.
        boolean anySnapshotCustom = false;
        for (EncryptedDataHandler<?> handler : registry.handlers()) {
            Metadata.ProjectCustom custom = project.custom(handler.customName());
            if (custom != null && custom.context().contains(Metadata.XContentContext.SNAPSHOT)) {
                anySnapshotCustom = true;
                break;
            }
        }
        if (anySnapshotCustom == false) {
            return metadata;
        }

        if (sealedKey == null) {
            logger.warn(
                "Snapshot will not include encrypted project data for project [{}]: "
                    + "no snapshot password was supplied. Re-configure the data manually after restore.",
                projectId.id()
            );
            return dropEncryptedData(metadata, project, registry);
        }

        return rewrapEncryptedData(metadata, project, registry, sealedKey);
    }

    @Override
    public boolean containsEncryptedData(ProjectId projectId, Metadata metadata) {
        EncryptedDataHandlerRegistry registry = EncryptedDataHandlerRegistry.getInstance();
        if (registry == null) {
            return false;
        }
        ProjectMetadata project = metadata.getProject(projectId);
        if (project == null) {
            return false;
        }
        for (EncryptedDataHandler<?> handler : registry.handlers()) {
            Metadata.ProjectCustom custom = project.custom(handler.customName());
            if (custom != null && custom.context().contains(Metadata.XContentContext.SNAPSHOT)) {
                return true;
            }
        }
        return false;
    }

    private Metadata dropEncryptedData(Metadata metadata, ProjectMetadata project, EncryptedDataHandlerRegistry registry) {
        return applyToSnapshotCustoms(metadata, project, registry, v -> null);
    }

    private Metadata rewrapEncryptedData(
        Metadata metadata,
        ProjectMetadata project,
        EncryptedDataHandlerRegistry registry,
        BytesReference sealedKey
    ) {
        final EncryptionService encryptionService = EncryptionServiceRegistry.getEncryptionService();
        final byte[] keyMaterial = encryptionService.decrypt(readSealedKey(sealedKey));
        try {
            if (keyMaterial.length != SALT_LENGTH_BYTES + KEK_LENGTH_BYTES) {
                throw new IllegalStateException("sealed snapshot key has unexpected length [" + keyMaterial.length + "]");
            }
            final byte[] salt = Arrays.copyOfRange(keyMaterial, 0, SALT_LENGTH_BYTES);
            final SecretKey kek = new SecretKeySpec(keyMaterial, SALT_LENGTH_BYTES, KEK_LENGTH_BYTES, "AES");
            return applyToSnapshotCustoms(metadata, project, registry, existing -> {
                byte[] plaintext = encryptionService.decrypt(existing);
                try {
                    return PasswordBasedEncryption.wrapWithKek(plaintext, SNAPSHOT_PASSWORD_KEY_ID, kek, salt);
                } finally {
                    Arrays.fill(plaintext, (byte) 0);
                }
            });
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Metadata applyToSnapshotCustoms(
        Metadata metadata,
        ProjectMetadata project,
        EncryptedDataHandlerRegistry registry,
        UnaryOperator<EncryptedData> rewrapper
    ) {
        ProjectMetadata.Builder projBuilder = null;
        for (EncryptedDataHandler<?> handler : registry.handlers()) {
            Metadata.ProjectCustom custom = project.custom(handler.customName());
            if (custom == null || custom.context().contains(Metadata.XContentContext.SNAPSHOT) == false) {
                continue;
            }
            Metadata.ProjectCustom result = ((EncryptedDataHandler) handler).reEncrypt(custom, rewrapper);
            if (result != custom) {
                if (projBuilder == null) {
                    projBuilder = ProjectMetadata.builder(project);
                }
                if (result == null) {
                    projBuilder.removeCustom(handler.customName());
                } else {
                    projBuilder.putCustom(handler.customName(), result);
                }
            }
        }
        if (projBuilder == null) {
            return metadata;
        }
        return Metadata.builder(metadata).put(projBuilder).build();
    }
}
