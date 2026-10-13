/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.cluster.snapshots.create;

import org.elasticsearch.features.FeatureSpecification;
import org.elasticsearch.features.NodeFeature;

import java.util.Set;

public class CreateSnapshotFeatures implements FeatureSpecification {
    /**
     * Every node understands {@code encrypted_data} on create-snapshot requests. Required before accepting one, since an older master
     * would never receive the field.
     */
    public static final NodeFeature ENCRYPTED_DATA = new NodeFeature("snapshots.create.encrypted_data");

    @Override
    public Set<NodeFeature> getFeatures() {
        return Set.of(ENCRYPTED_DATA);
    }
}
