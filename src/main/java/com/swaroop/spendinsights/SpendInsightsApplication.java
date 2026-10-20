package com.swaroop.spendinsights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class SpendInsightsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpendInsightsApplication.class, args);
    }
}
