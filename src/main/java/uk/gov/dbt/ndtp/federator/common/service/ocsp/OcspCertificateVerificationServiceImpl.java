package uk.gov.dbt.ndtp.federator.common.service.ocsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.utils.SSLUtils;
import uk.gov.dbt.ndtp.federator.exceptions.CertificateRevokedException;
import uk.gov.dbt.ndtp.federator.exceptions.OcspVerificationException;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Properties;

@Slf4j
public class OcspCertificateVerificationServiceImpl
        implements OcspCertificateVerificationService {



    private final String p12Password;      // from property: client.ssl.key-store-password
    private final String managementNodeBaseUrl;
    private final String clientId;
    private final HttpClient httpClient;
    private final OtelCertificateVerificationLogger otelLogger;
    private final Properties clientProps;
    private final IdpTokenService idpTokenService;

    public OcspCertificateVerificationServiceImpl(
            Properties clientProps, Properties commonProps, IdpTokenService idpTokenService) {
        this.clientProps = clientProps;
        this.p12Password     = clientProps.getProperty("client.p12Password");


        this.clientId        = commonProps.getProperty("idp.client.id");
        this.managementNodeBaseUrl = clientProps.getProperty("management.node.base.url");
        this.idpTokenService = idpTokenService;
        this.otelLogger = new OtelCertificateVerificationLogger();
        SSLContext sslContext = SSLUtils.createSSLContextWithTrustStore(
                clientProps.getProperty("client.truststoreFilePath"),
                clientProps.getProperty("client.truststorePassword"));

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(sslContext)
                .build();
    }
    @Override

    public void verifyBeforeConnect(String producerIdpClientId) {

        Instant timestamp = Instant.now();

        // Load cert and extract serial number

//        X509Certificate cert = loadClientCertificate();
//
//        String serialNumber = cert.getSerialNumber()
//
//                .toString(16)
//
//                .toUpperCase();

        OcspStatus status;

        try {

            // Pass both clientId AND serialNumber to AC3 endpoint

            status = checkCertificateStatus(producerIdpClientId);

        } catch (Exception e) {

            otelLogger.log(producerIdpClientId, timestamp, OcspStatus.NOT_FOUND);

            throw new OcspVerificationException(

                    "OCSP check failed for producer IdpClientId : " + producerIdpClientId, e);

        }

        otelLogger.log(clientId, timestamp, status);

        if (status == OcspStatus.REVOKED) {

            throw new CertificateRevokedException(

                    "Certificate is REVOKED/EXPIRED. clientId=" + clientId );

        }

    }

    private OcspStatus checkCertificateStatus(

            String clientId) throws Exception {

        // Build URL with both clientId and serialNumber

        String url = managementNodeBaseUrl +

                "/api/v1/certificate/ocsp?clientId=" + clientId ;

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

            case "ACTIVE"   -> OcspStatus.ACTIVE;

            case "REVOKED"  -> OcspStatus.REVOKED;

            case "EXPIRED"  -> OcspStatus.EXPIRED;

            default         -> OcspStatus.NOT_FOUND;

        };

    }



    private X509Certificate loadClientCertificate() {//soma undo with //clientProps.getProperty("client.P12");
        try (InputStream is = new FileInputStream(clientProps.getProperty("client.p12FilePath"))) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(is, p12Password.toCharArray());
            // Get first certificate entry from the keystore
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = ks.getCertificate(alias);
                if (cert instanceof X509Certificate x509) {
                    return x509;
                }
            }
            throw new OcspVerificationException("No X509Certificate found in P12 keystore");
        } catch (Exception e) {
            throw new OcspVerificationException("Failed to load client certificate", e);
        }
    }

}

