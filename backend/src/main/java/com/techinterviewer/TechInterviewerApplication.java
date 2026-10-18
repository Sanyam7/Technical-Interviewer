package com.techinterviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TechInterviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TechInterviewerApplication.class, args);
    }
}
