package com.sanlam.fintech.withdrawal;

import com.sanlam.fintech.withdrawal.config.WithdrawalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WithdrawalProperties.class)
public class WithdrawalApplication {

    public static void main(String[] args) {
        SpringApplication.run(WithdrawalApplication.class, args);
    }
}
