package org.jetlinks.community.saas.propertycatalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.InputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class PropertyCatalogInitializer implements CommandLineRunner {
    private final PropertyCatalogService service;
    private final ObjectMapper mapper;
    @Override
    public void run(String... args) throws Exception {
        ClassPathResource resource = new ClassPathResource("property-catalog/seed-v1.json");
        try (InputStream stream = resource.getInputStream()) {
            Boolean created = service.initialize(mapper.readTree(stream)).block();
            if (Boolean.TRUE.equals(created)) log.info("Initialized property library seed version 1");
        }
    }
}
