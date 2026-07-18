package com.webapp.bankingportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

import com.webapp.bankingportal.ratelimit.RateLimitProperties;

@SpringBootApplication
@EnableCaching // Add this annotation to enable caching support
@EnableAsync
@EnableConfigurationProperties(RateLimitProperties.class)
public class BankingportalApplication {

	public static void main(String[] args) {
		SpringApplication.run(BankingportalApplication.class, args);
	}

}
