package com.cryptoview.event;

import com.cryptoview.model.domain.OrderBook;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderBookUpdateEvent extends ApplicationEvent {

    private final OrderBook orderBook;

    public OrderBookUpdateEvent(Object source, OrderBook orderBook) {
        super(source);
        this.orderBook = orderBook;
    }
}
