package com.sanlam.fintech.withdrawal;

import com.sanlam.fintech.withdrawal.config.SanlamBankProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SanlamBankProperties.class)
public class SanlamBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(SanlamBankApplication.class, args);
    }
}
