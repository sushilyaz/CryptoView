package com.cryptoview.exchange.common;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;

import java.util.List;

public interface ExchangeConnector {

    Exchange getExchange();

    MarketType getMarketType();

    void connect();

    void disconnect();

    void subscribe(List<String> symbols);

    void subscribeAll();

    boolean isConnected();

    int getSubscribedSymbolsCount();

    default String getStatusSummary() {
        return "symbols=" + getSubscribedSymbolsCount();
    }
}
