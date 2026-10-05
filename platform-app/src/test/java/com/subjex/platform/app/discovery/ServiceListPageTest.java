package com.subjex.platform.app.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ServiceRegistryEndpoint.class, ServiceListPage.class})
@Import({
    PlatformSecurityConfiguration.class,
    PlatformExceptionAdvice.class,
    ServiceListPageTest.CatalogConfiguration.class
})
class ServiceListPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pageIsOneSentenceAndHasNoOpsKnobs() {
        String html = ServiceListPage.html(List.of(
                new ListedService("platform-app", "127.0.0.1", 9, AddressProbe.UP),
                new ListedService("sample-consumer", "127.0.0.1", 1, AddressProbe.UNKNOWN),
                new ListedService("<b>", "10.0.0.2", 2, "critical")));

        assertTrue(html.contains("这是一份只读的服务名单，只列出名字、地址，以及此刻能不能连上。"));
        assertTrue(html.contains(
                "This is a read-only service list: a name, an address, and whether it answers right now."));
        assertTrue(html.contains("服务名"));
        assertTrue(html.contains("service name"));
        assertTrue(html.contains("地址"));
        assertTrue(html.contains("address"));
        assertTrue(html.contains("状态"));
        assertTrue(html.contains("status"));
        assertTrue(html.contains(">up<"));
        assertTrue(html.contains(">unknown<"));
        assertTrue(html.contains("可用"));
        assertTrue(html.contains("未知"));
        assertTrue(html.contains("127.0.0.1:9"));
        assertTrue(html.contains("&lt;b&gt;"));
        assertFalse(html.contains("<form"));
        assertFalse(html.contains("<button"));
        assertFalse(html.contains("权重"));
        assertFalse(html.contains("metadata"));
        assertFalse(html.contains("namespace"));
        assertFalse(html.contains("topology"));
        assertFalse(html.contains("下线"));
        assertFalse(html.contains("critical"));
    }

    @Test
    void listAndPageRequireTheOperator() throws Exception {
        mockMvc.perform(get("/services")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/registry/services")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"platform-app\",\"host\":\"127.0.0.1\",\"port\":9}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorSeesTheSameStatusWordOnThePageAndTheList() throws Exception {
        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"platform-app\",\"host\":\"127.0.0.1\",\"port\":9}")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"sample-consumer\",\"host\":\"127.0.0.1\",\"port\":1}")
                        .with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/registry/services").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceName").value("platform-app"))
                .andExpect(jsonPath("$[0].status").value("up"))
                .andExpect(jsonPath("$[1].serviceName").value("sample-consumer"))
                .andExpect(jsonPath("$[1].status").value("unknown"));

        mockMvc.perform(get("/services").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">up<")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">unknown<")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("这是一份只读的服务名单")));
    }

    @Test
    void pageDoesNotAcceptAWrite() throws Exception {
        mockMvc.perform(post("/services").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
    }

    @TestConfiguration
    static class CatalogConfiguration {
        @Bean
        InProcessServiceRegistry localServiceRegistry() {
            return new InProcessServiceRegistry();
        }

        @Bean
        AddressProbe addressProbe() {
            return (host, port) -> port == 9 ? AddressProbe.UP : AddressProbe.UNKNOWN;
        }

        @Bean
        ServiceCatalog serviceCatalog(InProcessServiceRegistry localServiceRegistry, AddressProbe addressProbe) {
            return new ServiceCatalog(localServiceRegistry, addressProbe);
        }

        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
