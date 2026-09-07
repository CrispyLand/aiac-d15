package com.crispyland;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentChatApplication.class, args);
    }
}
