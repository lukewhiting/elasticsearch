/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.rest.streams.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.Scope;
import org.elasticsearch.rest.ServerlessScope;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;

@ServerlessScope(Scope.PUBLIC)
public class RestSetLogStreamsEnabledAction extends BaseRestHandler {
    @Override
    public String getName() {
        return "streams_logs_set_enabled_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(GET, "/_streams/logs/_enable"), new Route(GET, "/_streams/logs/_disable"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        final boolean enabled = request.path().endsWith("_enable");
        assert enabled || request.path().endsWith("_disable");
        return restChannel -> restChannel.sendResponse(new RestResponse(RestStatus.OK, "Logs endpoint enabled status set to " + enabled));
    }

}
