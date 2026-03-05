package com.cryptoview.exchange.common;

import com.cryptoview.model.enums.Exchange;
import com.cryptoview.model.enums.MarketType;

import java.util.List;
import java.util.Set;

public interface ExchangeConnector {

    Exchange getExchange();

    MarketType getMarketType();

    void connect();

    void disconnect();

    void subscribe(List<String> symbols);

    void subscribeAll();

    boolean isConnected();

    int getSubscribedSymbolsCount();

    Set<String> getSubscribedSymbols();

    default String getStatusSummary() {
        return "symbols=" + getSubscribedSymbolsCount();
    }
}
