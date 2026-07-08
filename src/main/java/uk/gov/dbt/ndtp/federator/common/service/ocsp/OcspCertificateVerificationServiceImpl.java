package uk.gov.dbt.ndtp.federator.common.service.ocsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.utils.HttpClientFactoryUtils;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.exceptions.OcspVerificationException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Properties;
import java.util.function.Supplier;

@Slf4j
public class OcspCertificateVerificationServiceImpl
        implements OcspCertificateVerificationService {


    private static String MANAGEMENT_NODE_BASE_URL_KEY = "management.node.base.url";


    private final String managementNodeBaseUrl;
    private final Supplier<java.net.http.HttpClient> httpClientSupplier;
    private final OtelCertificateVerificationLogger otelLogger;
    private final IdpTokenService idpTokenService;

    public OcspCertificateVerificationServiceImpl(
            Properties properties, IdpTokenService idpTokenService) {

        this.managementNodeBaseUrl = PropertyUtil.getPropertyValue(MANAGEMENT_NODE_BASE_URL_KEY);
        this.idpTokenService = idpTokenService;
        this.otelLogger = new OtelCertificateVerificationLogger();

        this.httpClientSupplier = () -> HttpClientFactoryUtils.createHttpClientWithMtls(properties);
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

                httpClientSupplier.get().send(request, HttpResponse.BodyHandlers.ofString());

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

