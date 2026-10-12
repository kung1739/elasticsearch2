/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.encryption;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest;
import org.elasticsearch.action.admin.cluster.snapshots.create.TransportCreateSnapshotAction;
import org.elasticsearch.cluster.AbstractNamedDiffable;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.NamedDiff;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ChunkedToXContentHelper;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.snapshots.AbstractSnapshotIntegTestCase;
import org.elasticsearch.snapshots.SnapshotEncryptedData;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xpack.encryption.spi.EncryptedData;
import org.elasticsearch.xpack.encryption.spi.EncryptedDataHandler;
import org.elasticsearch.xpack.encryption.spi.EncryptedDataHandlerProvider;
import org.elasticsearch.xpack.encryption.spi.EncryptionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies that PEK-encrypted project data supplied with a snapshot password ends up in the snapshot re-wrapped under that password,
 * including when a different master finalizes the snapshot than the one that accepted the request.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
public class SnapshotEncryptedDataIT extends AbstractSnapshotIntegTestCase {

    private static final String REPO = "test-repo";
    private static final String SNAPSHOT_PASSWORD = "correct-horse-battery-staple";

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        MockSecureSettings secure = new MockSecureSettings();
        secure.setString(ProjectEncryptionKeyPasswordSettings.ACTIVE_PASSWORD_ID_KEY, "v1");
        secure.setString(ProjectEncryptionKeyPasswordSettings.PASSWORD_PREFIX + "v1", "encryption-test-password");
        return Settings.builder().put(super.nodeSettings(nodeOrdinal, otherSettings)).setSecureSettings(secure).build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        Collection<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(EncryptionPlugin.class);
        plugins.add(TestBlobPlugin.class);
        return plugins;
    }

    public void testEncryptedDataSurvivesMasterFailover() throws Exception {
        internalCluster().startMasterOnlyNodes(3);
        final String dataNode = internalCluster().startDataOnlyNode();
        final byte[] secret = randomByteArrayOfLength(32);
        seedEncryptedBlob(secret);
        createRepository(REPO, "mock");
        createIndexWithContent("index");

        blockDataNode(REPO, dataNode);
        final CreateSnapshotRequest request = new CreateSnapshotRequest(TEST_REQUEST_TIMEOUT, REPO, "snap").includeGlobalState(true)
            .waitForCompletion(false)
            .encryptedData(
                new SnapshotEncryptedData(SnapshotEncryptedData.TYPE_PASSWORD, new SecureString(SNAPSHOT_PASSWORD.toCharArray()), "pw-1")
            );
        safeGet(client(dataNode).execute(TransportCreateSnapshotAction.TYPE, request));
        waitForBlock(dataNode, REPO);

        internalCluster().stopCurrentMasterNode();
        ensureStableCluster(3);
        unblockNode(REPO, dataNode);
        awaitNoMoreRunningOperations();

        final SnapshotInfo snapshotInfo = getSnapshot(REPO, "snap");
        assertThat(snapshotInfo.state(), equalTo(SnapshotState.SUCCESS));
        assertThat(snapshotInfo.encryptedDataType(), equalTo(SnapshotEncryptedData.TYPE_PASSWORD));
        assertThat(snapshotInfo.encryptedDataPasswordId(), equalTo("pw-1"));

        final TestBlob stored = blobInSnapshot(snapshotInfo);
        assertThat(stored, notNullValue());
        assertThat(stored.blob.keyId(), equalTo(EncryptingSnapshotGlobalStateTransformer.SNAPSHOT_PASSWORD_KEY_ID));
        assertArrayEquals(secret, PasswordBasedEncryption.unwrap(stored.blob, SNAPSHOT_PASSWORD.toCharArray()));
    }

    public void testEncryptedDataExcludedWithoutPassword() throws Exception {
        internalCluster().startMasterOnlyNode();
        internalCluster().startDataOnlyNode();
        seedEncryptedBlob(randomByteArrayOfLength(32));
        createRepository(REPO, "fs");
        createIndexWithContent("index");

        final SnapshotInfo snapshotInfo = createFullSnapshot(REPO, "snap");

        assertFalse(snapshotInfo.hasEncryptedData());
        assertNull(blobInSnapshot(snapshotInfo));
    }

    private TestBlob blobInSnapshot(SnapshotInfo snapshotInfo) {
        final Repository repository = getRepositoryOnMaster(REPO);
        return repository.getSnapshotGlobalMetadata(snapshotInfo.snapshotId(), false).getProject(ProjectId.DEFAULT).custom(TestBlob.TYPE);
    }

    /**
     * Waits for the initial project encryption key, then stores {@code secret} encrypted under it in a SNAPSHOT-context custom.
     */
    private void seedEncryptedBlob(byte[] secret) throws Exception {
        assertBusy(() -> {
            final ClusterService clusterService = internalCluster().getCurrentMasterNodeInstance(ClusterService.class);
            assertThat(clusterService.state().metadata().getSingleProjectCustom(ProjectEncryptionKeyMetadata.TYPE), notNullValue());
        });
        final EncryptedData encrypted = internalCluster().getCurrentMasterNodeInstance(EncryptionService.class).encrypt(secret);
        safeAwait(
            listener -> internalCluster().getCurrentMasterNodeInstance(ClusterService.class)
                .submitUnbatchedStateUpdateTask("seed-test-blob", new ClusterStateUpdateTask() {
                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        return currentState.copyAndUpdateProject(
                            ProjectId.DEFAULT,
                            b -> b.putCustom(TestBlob.TYPE, new TestBlob(encrypted))
                        );
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }

                    @Override
                    public void clusterStateProcessed(ClusterState initialState, ClusterState newState) {
                        listener.onResponse(null);
                    }
                })
        );
    }

    /** Registers {@link TestBlob}; its presence on a node also enables {@link TestBlobHandlerProvider}. */
    public static class TestBlobPlugin extends Plugin {
        @Override
        public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
            return List.of(
                new NamedWriteableRegistry.Entry(Metadata.ProjectCustom.class, TestBlob.TYPE, TestBlob::new),
                new NamedWriteableRegistry.Entry(NamedDiff.class, TestBlob.TYPE, TestBlob::readDiffFrom)
            );
        }

        @Override
        public List<NamedXContentRegistry.Entry> getNamedXContent() {
            return List.of(
                new NamedXContentRegistry.Entry(Metadata.ProjectCustom.class, new ParseField(TestBlob.TYPE), TestBlob::fromXContent)
            );
        }
    }

    /**
     * Loaded via {@code META-INF/services}. The constructor ties it to {@link TestBlobPlugin} so that other tests in this source set,
     * which don't install that plugin, don't get its handler.
     */
    public static final class TestBlobHandlerProvider implements EncryptedDataHandlerProvider {
        public TestBlobHandlerProvider(TestBlobPlugin plugin) {}

        @Override
        public Collection<EncryptedDataHandler<?>> getHandlers() {
            return List.of(new TestBlobHandler());
        }
    }

    private static final class TestBlobHandler implements EncryptedDataHandler<TestBlob> {
        @Override
        public String customName() {
            return TestBlob.TYPE;
        }

        @Override
        public TestBlob reEncrypt(TestBlob current, UnaryOperator<EncryptedData> rewrapper) {
            if (current == null) {
                return null;
            }
            final EncryptedData rewrapped = rewrapper.apply(current.blob);
            if (rewrapped == null) {
                return null;
            }
            return rewrapped == current.blob ? current : new TestBlob(rewrapped);
        }
    }

    public static final class TestBlob extends AbstractNamedDiffable<Metadata.ProjectCustom> implements Metadata.ProjectCustom {
        static final String TYPE = "snapshot_encrypted_data_it_blob";

        private static final ConstructingObjectParser<TestBlob, Void> PARSER = new ConstructingObjectParser<>(
            TYPE,
            args -> new TestBlob((EncryptedData) args[0])
        );

        static {
            PARSER.declareObject(constructorArg(), (p, c) -> EncryptedData.fromXContent(p), new ParseField("blob"));
        }

        final EncryptedData blob;

        TestBlob(EncryptedData blob) {
            this.blob = blob;
        }

        public TestBlob(StreamInput in) throws IOException {
            this.blob = new EncryptedData(in);
        }

        static TestBlob fromXContent(XContentParser parser) {
            return PARSER.apply(parser, null);
        }

        public static NamedDiff<Metadata.ProjectCustom> readDiffFrom(StreamInput in) throws IOException {
            return readDiffFrom(Metadata.ProjectCustom.class, TYPE, in);
        }

        @Override
        public String getWriteableName() {
            return TYPE;
        }

        @Override
        public TransportVersion getMinimalSupportedVersion() {
            return TransportVersion.current();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            blob.writeTo(out);
        }

        @Override
        public EnumSet<Metadata.XContentContext> context() {
            return EnumSet.of(Metadata.XContentContext.GATEWAY, Metadata.XContentContext.SNAPSHOT);
        }

        @Override
        public Iterator<? extends ToXContent> toXContentChunked(ToXContent.Params params) {
            return ChunkedToXContentHelper.chunk((builder, ignored) -> builder.field("blob", blob));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TestBlob other && other.blob.equals(blob);
        }

        @Override
        public int hashCode() {
            return blob.hashCode();
        }
    }
}
