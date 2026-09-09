package com.ilko.tournament.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaConfigurationTest {
    @Test
    void localDevelopmentUsesUpdateWhileProductionUsesValidate() throws IOException {
        assertEquals("update", load("application.properties").getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("validate", load("application-prod.properties").getProperty("spring.jpa.hibernate.ddl-auto"));
    }

    private Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing resource: " + resource);
            properties.load(stream);
        }
        return properties;
    }
}
