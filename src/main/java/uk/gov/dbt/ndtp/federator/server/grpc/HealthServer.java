package uk.gov.dbt.ndtp.federator.server.grpc;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;

import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;

/**
 * HTTPS Health Server for App Gateway probe
 */
public class HealthHttpsServer {

    private static final String SERVER_P12_FILE_PATH = "server.p12FilePath";
    private static final String SERVER_P12_PASSWORD = "server.p12Password";

    public static void start() {
        try {
            int port = 8443;

            // Load properties (same as GRPCServer)
            String p12FilePath = PropertyUtil.getPropertyValue(SERVER_P12_FILE_PATH);
            String p12Password = PropertyUtil.getPropertyValue(SERVER_P12_PASSWORD);

            char[] password = p12Password.toCharArray();

            // Load keystore
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new FileInputStream(p12FilePath), password);

            // Init KeyManager
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, password);

            // Init SSL context
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            // Create HTTPS server
            HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext));

            // Add /health endpoint
            server.createContext("/health", new HealthHandler());

            server.setExecutor(null);
            server.start();

            System.out.println("HTTPS Health Server started on port " + port);

        } catch (Exception e) {
            throw new RuntimeException("Failed to start HTTPS Health Server", e);
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                String response = "OK";

                exchange.sendResponseHeaders(200, response.length());

                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}