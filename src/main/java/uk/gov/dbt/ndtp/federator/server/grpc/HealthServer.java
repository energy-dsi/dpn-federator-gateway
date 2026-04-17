// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
 
package uk.gov.dbt.ndtp.federator.server.grpc;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Simple HTTP server for health check
 */
public class HealthServer {

    private static HttpServer server;

    public static void start() {
        try {
            // Create server on port 8080
            server = HttpServer.create(new InetSocketAddress(8080), 0);

            // Define /health endpoint
            server.createContext("/health", new HealthHandler());

            server.setExecutor(null); // default executor
            server.start();

            System.out.println("Health HTTP server started on port 8080");

        } catch (IOException e) {
            throw new RuntimeException("Failed to start Health HTTP Server", e);
        }
    }

    // Handler for /health
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {

            String response = "OK";

            // Always return 200
            exchange.sendResponseHeaders(200, response.length());

            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}