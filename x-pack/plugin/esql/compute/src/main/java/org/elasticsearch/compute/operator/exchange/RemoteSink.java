/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator.exchange;

import org.elasticsearch.action.ActionListener;

public interface RemoteSink {

    void fetchPageAsync(boolean allSourcesFinished, ActionListener<ExchangeResponse> listener);

    default void close(ActionListener<Void> listener) {
        fetchPageAsync(true, listener.delegateFailure((l, r) -> {
            try {
                r.close();
            } finally {
                l.onResponse(null);
            }
        }));
    }
}
