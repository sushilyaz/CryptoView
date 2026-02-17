package com.cryptoview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CryptoViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoViewApplication.class, args);
    }
}
