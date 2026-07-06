package uk.gov.dbt.ndtp.federator.common.service.ocsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.utils.SSLUtils;
import uk.gov.dbt.ndtp.federator.exceptions.OcspVerificationException;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

@Slf4j
public class OcspCertificateVerificationServiceImpl
        implements OcspCertificateVerificationService {




    private final String managementNodeBaseUrl;
    private final HttpClient httpClient;
    private final OtelCertificateVerificationLogger otelLogger;
    private final IdpTokenService idpTokenService;

    public OcspCertificateVerificationServiceImpl(
            Properties clientProps, IdpTokenService idpTokenService) {

        this.managementNodeBaseUrl = clientProps.getProperty("management.node.base.url");
        this.idpTokenService = idpTokenService;
        this.otelLogger = new OtelCertificateVerificationLogger();
        SSLContext sslContext = SSLUtils.createSSLContext(
                clientProps.getProperty("client.keystoreFilePath"),
                clientProps.getProperty("client.keystorePassword"),
                clientProps.getProperty("client.truststoreFilePath"),
                clientProps.getProperty("client.truststorePassword"));

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(sslContext)
                .build();
    }
    @Override

    public OcspStatus verifyBeforeConnect(String producerIdpClientId) {

        Instant timestamp = Instant.now();

        OcspStatus status = OcspStatus.NOT_FOUND;
        try {

            status = checkCertificateStatus(producerIdpClientId);


        } catch (Exception e) {
            log.error("OCSP check failed for clientId={}: {}", producerIdpClientId, e.getMessage(), e);
            otelLogger.log(producerIdpClientId, timestamp, OcspStatus.NOT_FOUND);
        }

        otelLogger.log(producerIdpClientId, timestamp, status);
        return status;
    }

    private OcspStatus checkCertificateStatus(

            String clientId) throws Exception {


        String url = managementNodeBaseUrl +

                "/api/v1/certificate/ocsp?clientId=" + clientId;


        String token = idpTokenService.fetchToken();

        HttpRequest request = HttpRequest.newBuilder()

                .uri(URI.create(url))

                .header("Authorization", "Bearer " + token)

                .header("Content-Type", "application/json")

                .GET()

                .build();

        HttpResponse<String> response =

                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {

            throw new OcspVerificationException(

                    "OCSP endpoint returned HTTP " + response.statusCode());

        }

        ObjectMapper mapper = new ObjectMapper();

        JsonNode json = mapper.readTree(response.body());

        String status = json.get("status").asText();

        return switch (status) {

            case "ACTIVE" -> OcspStatus.ACTIVE;

            case "REVOKED" -> OcspStatus.REVOKED;

            case "EXPIRED" -> OcspStatus.EXPIRED;

            default -> OcspStatus.NOT_FOUND;

        };

    }

}

