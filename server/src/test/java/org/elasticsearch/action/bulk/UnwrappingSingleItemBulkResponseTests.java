/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.bulk;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.index.mapper.MapperException;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class UnwrappingSingleItemBulkResponseTests extends ESTestCase {

    /**
     * The single document APIs report the failure of the only bulk item as an exception. When the item could not be stored in the failure
     * store either, that failure has to stay visible on the exception, as a suppressed exception of the cause.
     */
    public void testFailureStoreFailuresAreSuppressedOnTheCause() {
        BulkItemResponse.Failure failure = new BulkItemResponse.Failure(
            "index",
            "id",
            new MapperException("original failure"),
            IndexDocFailureStoreStatus.FAILED
        );
        failure.addFailureStoreFailure(new IllegalStateException("failure store failure"));
        BulkResponse bulkResponse = new BulkResponse(
            new BulkItemResponse[] { BulkItemResponse.failure(0, DocWriteRequest.OpType.CREATE, failure) },
            1L,
            BulkResponse.NO_INGEST_TOOK
        );

        PlainActionFuture<IndexResponse> future = new PlainActionFuture<>();
        TransportBulkAction.unwrappingSingleItemBulkResponse(future).onResponse(bulkResponse);

        ElasticsearchException exception = expectThrows(ElasticsearchException.class, future::actionGet);
        assertThat(exception, instanceOf(IndexDocFailureStoreStatus.ExceptionWithFailureStoreStatus.class));
        Throwable cause = exception.getCause();
        assertThat(cause, instanceOf(MapperException.class));
        assertThat(cause.getMessage(), equalTo("original failure"));
        assertThat(cause.getSuppressed().length, equalTo(1));
        assertThat(cause.getSuppressed()[0].getMessage(), equalTo("failure store failure"));
    }
}
