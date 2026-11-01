package com.tickwise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TickwiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(TickwiseApplication.class, args);
    }
}
