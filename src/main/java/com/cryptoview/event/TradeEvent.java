package com.cryptoview.event;

import com.cryptoview.model.domain.Trade;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class TradeEvent extends ApplicationEvent {

    private final Trade trade;

    public TradeEvent(Object source, Trade trade) {
        super(source);
        this.trade = trade;
    }
}
