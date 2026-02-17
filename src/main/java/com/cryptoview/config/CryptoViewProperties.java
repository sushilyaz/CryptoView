package com.cryptoview.config;

import com.cryptoview.model.config.ExchangeConfig;
import com.cryptoview.model.config.GlobalConfig;
import com.cryptoview.model.enums.Exchange;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "cryptoview")
public class CryptoViewProperties {

    private TelegramConfig telegram = new TelegramConfig();
    private GlobalConfig global = new GlobalConfig();
    private Map<Exchange, ExchangeConfig> exchanges = new HashMap<>();

    @Data
    public static class TelegramConfig {
        private String botToken;
        private List<String> chatIds;
        private boolean enabled = true;
    }
}
