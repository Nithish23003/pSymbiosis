package com.psiog.perfinsight.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@org.springframework.context.annotation.Configuration
public class JsonConfig {

    @Bean
    public Configuration jsonPathConfiguration(ObjectMapper objectMapper) {
        return Configuration.builder()
                .jsonProvider(new JacksonJsonProvider(objectMapper))
                .mappingProvider(new JacksonMappingProvider(objectMapper))
                .options(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)
                .build();
    }

    @Bean
    public RestClient.Builder connectorRestClientBuilder() {
        return RestClient.builder();
    }
}
