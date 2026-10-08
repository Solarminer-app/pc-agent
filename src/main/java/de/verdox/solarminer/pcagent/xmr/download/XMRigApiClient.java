package de.verdox.solarminer.pcagent.xmr.download;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class XMRigApiClient {

    private final RestClient restClient;

    public XMRigApiClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.xmrig.com/1")
                .build();
    }

    public XMRigRelease getLatestXmrigRelease() {
        return this.restClient.get()
                .uri("/latest_release")
                .retrieve()
                .body(XMRigRelease.class);
    }

    public XMRigRelease getLatestReleaseByProject(String project) {
        return this.restClient.get()
                .uri("/latest_release/{project}", project)
                .retrieve()
                .body(XMRigRelease.class);
    }

    public XMRigRelease checkForUpdates(String currentVersion) {
        return this.restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/latest_release")
                        .queryParam("version_gt", currentVersion)
                        .build())
                .retrieve()
                .body(XMRigRelease.class);
    }
}
