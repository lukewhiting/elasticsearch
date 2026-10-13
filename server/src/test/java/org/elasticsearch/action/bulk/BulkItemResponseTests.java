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
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.BulkItemResponse.Failure;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.delete.DeleteResponseTests;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.index.IndexResponseTests;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.action.update.UpdateResponseTests;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.TransportVersionUtils;
import org.elasticsearch.test.rest.ObjectPath;
import org.elasticsearch.transport.RemoteTransportException;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.ElasticsearchExceptionTests.assertDeepEquals;
import static org.elasticsearch.ElasticsearchExceptionTests.randomExceptions;
import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.elasticsearch.common.xcontent.XContentParserUtils.throwUnknownField;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

public class BulkItemResponseTests extends ESTestCase {

    /**
     * Parse the output of the {@link DocWriteResponse#innerToXContent(XContentBuilder, ToXContent.Params)} method.
     *
     * This method is intended to be called by subclasses and must be called multiple times to parse all the information concerning
     * {@link DocWriteResponse} objects. It always parses the current token, updates the given parsing context accordingly
     * if needed and then immediately returns.
     */
    public static void parseInnerToXContent(XContentParser parser, DocWriteResponse.Builder context) throws IOException {
        XContentParser.Token token = parser.currentToken();
        ensureExpectedToken(XContentParser.Token.FIELD_NAME, token, parser);

        String currentFieldName = parser.currentName();
        token = parser.nextToken();

        if (token.isValue()) {
            if (DocWriteResponse._INDEX.equals(currentFieldName)) {
                // index uuid and shard id are unknown and can't be parsed back for now.
                context.setShardId(new ShardId(new Index(parser.text(), IndexMetadata.INDEX_UUID_NA_VALUE), -1));
            } else if (DocWriteResponse._ID.equals(currentFieldName)) {
                context.setId(parser.text());
            } else if (DocWriteResponse._VERSION.equals(currentFieldName)) {
                context.setVersion(parser.longValue());
            } else if (DocWriteResponse.RESULT.equals(currentFieldName)) {
                String result = parser.text();
                for (DocWriteResponse.Result r : DocWriteResponse.Result.values()) {
                    if (r.getLowercase().equals(result)) {
                        context.setResult(r);
                        break;
                    }
                }
            } else if (DocWriteResponse.FORCED_REFRESH.equals(currentFieldName)) {
                context.setForcedRefresh(parser.booleanValue());
            } else if (DocWriteResponse._SEQ_NO.equals(currentFieldName)) {
                context.setSeqNo(parser.longValue());
            } else if (DocWriteResponse._PRIMARY_TERM.equals(currentFieldName)) {
                context.setPrimaryTerm(parser.longValue());
            }
        } else if (token == XContentParser.Token.START_OBJECT) {
            if (DocWriteResponse._SHARDS.equals(currentFieldName)) {
                context.setShardInfo(ReplicationResponse.ShardInfo.fromXContent(parser));
            } else {
                parser.skipChildren(); // skip potential inner objects for forward compatibility
            }
        } else if (token == XContentParser.Token.START_ARRAY) {
            parser.skipChildren(); // skip potential inner arrays for forward compatibility
        }
    }

    public void testFailureToString() {
        Failure failure = new Failure("index", "id", new RuntimeException("test"));
        String toString = failure.toString();
        assertThat(toString, containsString("\"type\":\"runtime_exception\""));
        assertThat(toString, containsString("\"reason\":\"test\""));
        assertThat(toString, containsString("\"status\":500"));
    }

    public void testToAndFromXContent() throws IOException {
        final XContentType xContentType = randomFrom(XContentType.values());

        for (DocWriteRequest.OpType opType : DocWriteRequest.OpType.values()) {
            int bulkItemId = randomIntBetween(0, 100);
            boolean humanReadable = randomBoolean();

            Tuple<? extends DocWriteResponse, ? extends DocWriteResponse> randomDocWriteResponses = null;
            if (opType == DocWriteRequest.OpType.INDEX || opType == DocWriteRequest.OpType.CREATE) {
                randomDocWriteResponses = IndexResponseTests.randomIndexResponse();
            } else if (opType == DocWriteRequest.OpType.DELETE) {
                randomDocWriteResponses = DeleteResponseTests.randomDeleteResponse();
            } else if (opType == DocWriteRequest.OpType.UPDATE) {
                randomDocWriteResponses = UpdateResponseTests.randomUpdateResponse(xContentType);
            } else {
                fail("Test does not support opType [" + opType + "]");
            }

            BulkItemResponse bulkItemResponse = BulkItemResponse.success(bulkItemId, opType, randomDocWriteResponses.v1());
            BulkItemResponse expectedBulkItemResponse = BulkItemResponse.success(bulkItemId, opType, randomDocWriteResponses.v2());
            BytesReference originalBytes = toShuffledXContent(bulkItemResponse, xContentType, ToXContent.EMPTY_PARAMS, humanReadable);

            BulkItemResponse parsedBulkItemResponse;
            try (XContentParser parser = createParser(xContentType.xContent(), originalBytes)) {
                assertEquals(XContentParser.Token.START_OBJECT, parser.nextToken());
                parsedBulkItemResponse = itemResponseFromXContent(parser, bulkItemId);
                assertNull(parser.nextToken());
            }
            assertBulkItemResponse(expectedBulkItemResponse, parsedBulkItemResponse);
        }
    }

    public void testFailureToAndFromXContent() throws IOException {
        final XContentType xContentType = randomFrom(XContentType.values());

        int itemId = randomIntBetween(0, 100);
        String index = randomAlphaOfLength(5);
        String id = randomAlphaOfLength(5);
        DocWriteRequest.OpType opType = randomFrom(DocWriteRequest.OpType.values());

        final Tuple<Throwable, ElasticsearchException> exceptions = randomExceptions();

        Exception bulkItemCause = (Exception) exceptions.v1();
        Failure bulkItemFailure = new Failure(index, id, bulkItemCause);
        BulkItemResponse bulkItemResponse = BulkItemResponse.failure(itemId, opType, bulkItemFailure);
        Failure expectedBulkItemFailure = new Failure(index, id, exceptions.v2(), ExceptionsHelper.status(bulkItemCause));
        BulkItemResponse expectedBulkItemResponse = BulkItemResponse.failure(itemId, opType, expectedBulkItemFailure);
        BytesReference originalBytes = toShuffledXContent(bulkItemResponse, xContentType, ToXContent.EMPTY_PARAMS, randomBoolean());

        // Shuffle the XContent fields
        if (randomBoolean()) {
            try (XContentParser parser = createParser(xContentType.xContent(), originalBytes)) {
                originalBytes = BytesReference.bytes(shuffleXContent(parser, randomBoolean()));
            }
        }

        BulkItemResponse parsedBulkItemResponse;
        try (XContentParser parser = createParser(xContentType.xContent(), originalBytes)) {
            assertEquals(XContentParser.Token.START_OBJECT, parser.nextToken());
            parsedBulkItemResponse = itemResponseFromXContent(parser, itemId);
            assertNull(parser.nextToken());
        }
        assertBulkItemResponse(expectedBulkItemResponse, parsedBulkItemResponse);
    }

    /**
     * The failure store failures of an item are rendered as the last suppressed exceptions of its cause, after the ones that the cause
     * already has, without being added to the cause itself, which other items can share.
     */
    public void testFailureStoreFailuresAreRenderedAsLastSuppressedExceptions() throws IOException {
        Exception cause = new ElasticsearchException("original failure");
        cause.addSuppressed(new IllegalStateException("existing suppressed"));
        Failure failure = new Failure("index", "id", cause);
        failure.setFailureStoreStatus(IndexDocFailureStoreStatus.FAILED);
        failure.addFailureStoreFailure(new IllegalArgumentException("failure store failure"));
        BulkItemResponse item = BulkItemResponse.failure(0, DocWriteRequest.OpType.CREATE, failure);

        ObjectPath itemJson = ObjectPath.createFromXContent(
            JsonXContent.jsonXContent,
            XContentHelper.toXContent(item, XContentType.JSON, randomBoolean())
        );
        assertThat(itemJson.evaluate("create.error.reason"), equalTo("original failure"));
        assertThat(itemJson.evaluate("create.error.suppressed.0.reason"), equalTo("existing suppressed"));
        assertThat(itemJson.evaluate("create.error.suppressed.1.reason"), equalTo("failure store failure"));
        assertThat(itemJson.evaluate("create.failure_store"), equalTo("failed"));

        ObjectPath failureJson = ObjectPath.createFromXContent(JsonXContent.jsonXContent, new BytesArray(Strings.toString(failure)));
        assertThat(failureJson.evaluate("cause.suppressed.0.reason"), equalTo("existing suppressed"));
        assertThat(failureJson.evaluate("cause.suppressed.1.reason"), equalTo("failure store failure"));

        assertThat(failure.getCause(), sameInstance(cause));
        assertThat(cause.getSuppressed().length, equalTo(1));
    }

    public void testNoSuppressedExceptionsWithoutFailureStoreFailures() throws IOException {
        Failure failure = new Failure("index", "id", new ElasticsearchException("original failure"));
        BulkItemResponse item = BulkItemResponse.failure(0, DocWriteRequest.OpType.CREATE, failure);

        ObjectPath itemJson = ObjectPath.createFromXContent(
            JsonXContent.jsonXContent,
            XContentHelper.toXContent(item, XContentType.JSON, randomBoolean())
        );
        assertThat(itemJson.evaluate("create.error.suppressed"), nullValue());
        assertThat(failure.getFailureStoreFailures().length, equalTo(0));
    }

    /**
     * A wrapper exception is rendered as the exception that it wraps, which has no knowledge of what was suppressed on the wrapper. This is
     * what already happened when the failure store failure was added to the cause itself, so the rendering must not change.
     */
    public void testFailureStoreFailuresOfAWrapperCauseAreNotRendered() {
        Exception cause = new RemoteTransportException("remote", new ElasticsearchException("original failure"));
        Failure without = new Failure("index", "id", cause);
        Failure with = new Failure("index", "id", cause);
        with.addFailureStoreFailure(new IllegalStateException("failure store failure"));

        assertThat(Strings.toString(with), equalTo(Strings.toString(without)));
    }

    /**
     * Items that share one cause each have their own failure store failures, and the shared cause is not modified.
     */
    public void testFailureStoreFailuresOfItemsSharingACauseStayApart() {
        Exception sharedCause = new ElasticsearchException("original failure");
        Failure first = new Failure("index", "1", sharedCause);
        Failure second = new Failure("index", "2", sharedCause);

        first.addFailureStoreFailure(new IllegalStateException("first failure store failure"));
        second.addFailureStoreFailure(new IllegalStateException("second failure store failure"));

        assertThat(first.getCause(), sameInstance(sharedCause));
        assertThat(second.getCause(), sameInstance(sharedCause));
        assertThat(sharedCause.getSuppressed().length, equalTo(0));
        assertThat(first.getFailureStoreFailures().length, equalTo(1));
        assertThat(first.getFailureStoreFailures()[0].getMessage(), equalTo("first failure store failure"));
        assertThat(second.getFailureStoreFailures().length, equalTo(1));
        assertThat(second.getFailureStoreFailures()[0].getMessage(), equalTo("second failure store failure"));
    }

    public void testMergeFailureStoreFailuresIntoCause() {
        Exception cause = new ElasticsearchException("original failure");
        Failure failure = new Failure("index", "id", cause);
        failure.addFailureStoreFailure(new IllegalStateException("failure store failure"));

        failure.mergeFailureStoreFailuresIntoCause();

        assertThat(failure.getCause(), sameInstance(cause));
        assertThat(cause.getSuppressed().length, equalTo(1));
        assertThat(cause.getSuppressed()[0].getMessage(), equalTo("failure store failure"));
        assertThat(failure.getFailureStoreFailures().length, equalTo(0));
    }

    /**
     * The failure store failures are sent as suppressed exceptions of a copy of the cause, which is what every version reads.
     */
    public void testFailureStoreFailuresAreSuppressedOnTheCauseAfterTransport() throws IOException {
        Exception cause = new ElasticsearchException("original failure");
        cause.addSuppressed(new IllegalStateException("existing suppressed"));
        Failure failure = new Failure("index", "id", cause);
        failure.addFailureStoreFailure(new IllegalArgumentException("failure store failure"));

        Failure received = copyWriteable(failure, writableRegistry(), Failure::new);

        assertThat(received.getCause(), instanceOf(ElasticsearchException.class));
        assertThat(received.getCause().getMessage(), equalTo("original failure"));
        assertThat(received.getCause().getSuppressed().length, equalTo(2));
        assertThat(received.getCause().getSuppressed()[0].getMessage(), equalTo("existing suppressed"));
        assertThat(received.getCause().getSuppressed()[1].getMessage(), equalTo("failure store failure"));
        assertThat(received.getFailureStoreFailures().length, equalTo(0));
        assertThat(cause.getSuppressed().length, equalTo(1));
    }

    public void testFailureStoreFailuresAreSuppressedOnTheCauseAfterTransportToAnyVersion() throws IOException {
        Failure failure = new Failure("index", "id", new ElasticsearchException("original failure"));
        failure.addFailureStoreFailure(new IllegalArgumentException("failure store failure"));

        Failure received = copyWriteable(failure, writableRegistry(), Failure::new, TransportVersionUtils.randomCompatibleVersion());

        // Older versions can write an exception in a generic form that prefixes the message with the exception name
        assertThat(received.getCause().getMessage(), containsString("original failure"));
        assertThat(received.getCause().getSuppressed().length, equalTo(1));
        assertThat(received.getCause().getSuppressed()[0].getMessage(), containsString("failure store failure"));
    }

    /**
     * An item must render the same whether it is rendered where it was created or after it was sent to another node, for every kind of
     * cause: registered exceptions, built-in exception types that have their own wire format, exceptions that are not serializable, and
     * wrappers.
     */
    public void testFailureStoreFailuresRenderTheSameBeforeAndAfterTransport() throws IOException {
        for (Exception cause : List.of(
            new ElasticsearchException("original failure"),
            new IllegalArgumentException("original failure"),
            new RuntimeException("original failure"),
            new RemoteTransportException("remote", new ElasticsearchException("original failure")),
            new RemoteTransportException("remote", new IllegalArgumentException("original failure"))
        )) {
            Failure failure = new Failure("index", "id", cause);
            failure.addFailureStoreFailure(new IllegalStateException("failure store failure"));

            Failure received = copyWriteable(failure, writableRegistry(), Failure::new);

            assertThat("for " + cause, suppressedReasons(received), equalTo(suppressedReasons(failure)));
        }
    }

    private static List<Object> suppressedReasons(Failure failure) throws IOException {
        ObjectPath json = ObjectPath.createFromXContent(JsonXContent.jsonXContent, new BytesArray(Strings.toString(failure)));
        List<Object> suppressed = json.evaluate("cause.suppressed");
        List<Object> reasons = new ArrayList<>();
        for (int i = 0; suppressed != null && i < suppressed.size(); i++) {
            reasons.add(json.evaluate("cause.suppressed." + i + ".reason"));
        }
        return reasons;
    }

    public static void assertBulkItemResponse(BulkItemResponse expected, BulkItemResponse actual) {
        assertEquals(expected.getItemId(), actual.getItemId());
        assertEquals(expected.getIndex(), actual.getIndex());
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getOpType(), actual.getOpType());
        assertEquals(expected.getVersion(), actual.getVersion());
        assertEquals(expected.isFailed(), actual.isFailed());

        if (expected.isFailed()) {
            BulkItemResponse.Failure expectedFailure = expected.getFailure();
            BulkItemResponse.Failure actualFailure = actual.getFailure();

            assertEquals(expectedFailure.getIndex(), actualFailure.getIndex());
            assertEquals(expectedFailure.getId(), actualFailure.getId());
            assertEquals(expectedFailure.getMessage(), actualFailure.getMessage());
            assertEquals(expectedFailure.getStatus(), actualFailure.getStatus());

            assertDeepEquals((ElasticsearchException) expectedFailure.getCause(), (ElasticsearchException) actualFailure.getCause());
        } else {
            DocWriteResponse expectedDocResponse = expected.getResponse();
            DocWriteResponse actualDocResponse = expected.getResponse();

            IndexResponseTests.assertDocWriteResponse(expectedDocResponse, actualDocResponse);
            if (expected.getOpType() == DocWriteRequest.OpType.UPDATE) {
                assertEquals(((UpdateResponse) expectedDocResponse).getGetResult(), ((UpdateResponse) actualDocResponse).getGetResult());
            }
        }
    }

    /**
     * Reads a {@link BulkItemResponse} from a {@link XContentParser}.
     *
     * @param parser the {@link XContentParser}
     * @param id the id to assign to the parsed {@link BulkItemResponse}. It is usually the index of
     *           the item in the {@link BulkResponse#getItems} array.
     */
    public static BulkItemResponse itemResponseFromXContent(XContentParser parser, int id) throws IOException {
        ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);

        XContentParser.Token token = parser.nextToken();
        ensureExpectedToken(XContentParser.Token.FIELD_NAME, token, parser);

        String currentFieldName = parser.currentName();
        token = parser.nextToken();

        final DocWriteRequest.OpType opType = DocWriteRequest.OpType.fromString(currentFieldName);
        ensureExpectedToken(XContentParser.Token.START_OBJECT, token, parser);

        DocWriteResponse.Builder builder = null;
        CheckedConsumer<XContentParser, IOException> itemParser = null;

        if (opType == DocWriteRequest.OpType.INDEX || opType == DocWriteRequest.OpType.CREATE) {
            final IndexResponse.Builder indexResponseBuilder = new IndexResponse.Builder();
            builder = indexResponseBuilder;
            itemParser = indexParser -> parseInnerToXContent(indexParser, indexResponseBuilder);
        } else if (opType == DocWriteRequest.OpType.UPDATE) {
            final UpdateResponse.Builder updateResponseBuilder = new UpdateResponse.Builder();
            builder = updateResponseBuilder;
            itemParser = updateParser -> UpdateResponseTests.parseXContentFields(updateParser, updateResponseBuilder);

        } else if (opType == DocWriteRequest.OpType.DELETE) {
            final DeleteResponse.Builder deleteResponseBuilder = new DeleteResponse.Builder();
            builder = deleteResponseBuilder;
            itemParser = deleteParser -> parseInnerToXContent(deleteParser, deleteResponseBuilder);
        } else {
            throwUnknownField(currentFieldName, parser);
        }

        RestStatus status = null;
        ElasticsearchException exception = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            }

            if (BulkItemResponse.ERROR.equals(currentFieldName)) {
                if (token == XContentParser.Token.START_OBJECT) {
                    exception = ElasticsearchException.fromXContent(parser);
                }
            } else if (BulkItemResponse.STATUS.equals(currentFieldName)) {
                if (token == XContentParser.Token.VALUE_NUMBER) {
                    status = RestStatus.fromCode(parser.intValue());
                }
            } else {
                itemParser.accept(parser);
            }
        }

        ensureExpectedToken(XContentParser.Token.END_OBJECT, token, parser);
        token = parser.nextToken();
        ensureExpectedToken(XContentParser.Token.END_OBJECT, token, parser);

        BulkItemResponse bulkItemResponse;
        if (exception != null) {
            Failure failure = new Failure(builder.getShardId().getIndexName(), builder.getId(), exception, status);
            bulkItemResponse = BulkItemResponse.failure(id, opType, failure);
        } else {
            bulkItemResponse = BulkItemResponse.success(id, opType, builder.build());
        }
        return bulkItemResponse;
    }

    public void testStatusDelegatesToResponseOnSuccess() {
        IndexResponse response = IndexResponseTests.randomIndexResponse().v1();
        BulkItemResponse item = BulkItemResponse.success(0, DocWriteRequest.OpType.INDEX, response);
        assertEquals(response.status(), item.status());
    }

    public void testGetFailureStoreStatusDelegatesToResponse() {
        IndexResponse response = IndexResponseTests.randomIndexResponse().v1();
        response.setFailureStoreStatus(IndexDocFailureStoreStatus.FAILED);
        BulkItemResponse item = BulkItemResponse.success(0, DocWriteRequest.OpType.INDEX, response);
        assertEquals(IndexDocFailureStoreStatus.FAILED, item.getFailureStoreStatus());
    }

    public void testGetVersionReturnsMinusOneOnFailure() {
        Failure failure = new Failure("index", "id", new RuntimeException("Exception"));
        BulkItemResponse item = BulkItemResponse.failure(0, DocWriteRequest.OpType.INDEX, failure);
        assertEquals(-1L, item.getVersion());
    }

    public void testFailureSeqNoAndPrimaryTerm() {
        long seqNo = randomNonNegativeLong();
        long term = randomNonNegativeLong();

        Failure failure = new Failure("index", "id", new IllegalStateException("Exception"), seqNo, term);

        assertEquals(seqNo, failure.getSeqNo());
        assertEquals(term, failure.getTerm());
    }

    public void testFailureUnassignedSeqNoAndTermDefaults() {
        Failure failure = new Failure("index", "id", new RuntimeException("Exception"));

        assertEquals(SequenceNumbers.UNASSIGNED_SEQ_NO, failure.getSeqNo());
        assertEquals(SequenceNumbers.UNASSIGNED_PRIMARY_TERM, failure.getTerm());
    }

    public void testFailureAbortedFlag() {
        Failure abortedFailure = new Failure("index", "id", new RuntimeException("Exception"), true);

        assertTrue(abortedFailure.isAborted());
        assertEquals(RestStatus.INTERNAL_SERVER_ERROR, abortedFailure.getStatus());
    }

    public void testFailureStoreStatus() {
        Failure failure = new Failure("index", "id", new RuntimeException("boom"));

        assertEquals(IndexDocFailureStoreStatus.NOT_APPLICABLE_OR_UNKNOWN, failure.getFailureStoreStatus());

        failure.setFailureStoreStatus(IndexDocFailureStoreStatus.FAILED);

        assertEquals(IndexDocFailureStoreStatus.FAILED, failure.getFailureStoreStatus());
    }
}
