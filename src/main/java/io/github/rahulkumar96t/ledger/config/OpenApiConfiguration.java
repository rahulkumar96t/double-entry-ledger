package io.github.rahulkumar96t.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Double-entry ledger API")
                        .version("v1")
                        .description("""
                                Accounts, balanced journal entries and transfers. Amounts are integers in the \
                                currency's minor unit (cents for EUR, yen for JPY) and are stored debit-positive. \
                                POST endpoints accept an Idempotency-Key header. Errors use application/problem+json \
                                (RFC 9457, formerly RFC 7807).""")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
