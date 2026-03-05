package com.cryptoview.exchange.lighter;

import com.cryptoview.model.enums.MarketType;
import com.cryptoview.service.orderbook.OrderBookManager;
import com.cryptoview.service.volume.VolumeTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

@Component
public class LighterFuturesConnector extends AbstractLighterConnector {

    public LighterFuturesConnector(OkHttpClient httpClient,
                                    ObjectMapper objectMapper,
                                    OrderBookManager orderBookManager,
                                    VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public MarketType getMarketType() {
        return MarketType.FUTURES;
    }

    @Override
    protected String getMarketFilter() {
        return "perp";
    }

    @Override
    protected String buildSymbolName(String baseSymbol) {
        // Lighter perp symbols: "ETH" -> "ETHUSDC"
        return baseSymbol.toUpperCase() + "USDC";
    }
}
