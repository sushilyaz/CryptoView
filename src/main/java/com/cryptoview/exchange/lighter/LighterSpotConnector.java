package com.cryptoview.exchange.lighter;

import com.cryptoview.model.enums.MarketType;
import com.cryptoview.service.orderbook.OrderBookManager;
import com.cryptoview.service.volume.VolumeTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

@Component
public class LighterSpotConnector extends AbstractLighterConnector {

    public LighterSpotConnector(OkHttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 OrderBookManager orderBookManager,
                                 VolumeTracker volumeTracker) {
        super(httpClient, objectMapper, orderBookManager, volumeTracker);
    }

    @Override
    public MarketType getMarketType() {
        return MarketType.SPOT;
    }

    @Override
    protected String getMarketFilter() {
        return "spot";
    }

    @Override
    protected String buildSymbolName(String baseSymbol) {
        // Lighter spot symbols: "ETH" -> "ETHUSDC"
        return baseSymbol.toUpperCase() + "USDC";
    }
}
