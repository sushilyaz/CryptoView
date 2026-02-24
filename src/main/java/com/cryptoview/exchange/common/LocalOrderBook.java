package com.cryptoview.exchange.common;

import com.cryptoview.model.domain.OrderBookLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe local orderbook that supports snapshot + delta (incremental) updates.
 * Used by all exchange connectors that use diff-based depth channels.
 *
 * Bids are sorted by price descending, asks by price ascending.
 * When a delta has quantity=0, the price level is removed.
 */
public class LocalOrderBook {

    private final String symbol;
    private final TreeMap<BigDecimal, BigDecimal> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, BigDecimal> asks = new TreeMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private long lastUpdateId;
    private long lastSeqId;
    private volatile boolean initialized;
    private volatile Instant lastUpdateTime;

    public LocalOrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public long getLastUpdateId() {
        return lastUpdateId;
    }

    public long getLastSeqId() {
        return lastSeqId;
    }

    public Instant getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Apply a full snapshot — replaces entire orderbook state.
     */
    public void applySnapshot(List<List<String>> bidLevels, List<List<String>> askLevels,
                               long updateId) {
        lock.writeLock().lock();
        try {
            bids.clear();
            asks.clear();

            applyLevels(bids, bidLevels);
            applyLevels(asks, askLevels);

            this.lastUpdateId = updateId;
            this.initialized = true;
            this.lastUpdateTime = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Apply a full snapshot with seqId tracking (Bybit, OKX).
     */
    public void applySnapshot(List<List<String>> bidLevels, List<List<String>> askLevels,
                               long updateId, long seqId) {
        lock.writeLock().lock();
        try {
            bids.clear();
            asks.clear();

            applyLevels(bids, bidLevels);
            applyLevels(asks, askLevels);

            this.lastUpdateId = updateId;
            this.lastSeqId = seqId;
            this.initialized = true;
            this.lastUpdateTime = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Apply delta update — insert/update/remove individual price levels.
     * qty=0 means remove, otherwise insert or update.
     */
    public void applyDelta(List<List<String>> bidUpdates, List<List<String>> askUpdates,
                            long updateId) {
        lock.writeLock().lock();
        try {
            applyDeltaLevels(bids, bidUpdates);
            applyDeltaLevels(asks, askUpdates);

            this.lastUpdateId = updateId;
            this.lastUpdateTime = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Apply delta update with seqId tracking.
     */
    public void applyDelta(List<List<String>> bidUpdates, List<List<String>> askUpdates,
                            long updateId, long seqId) {
        lock.writeLock().lock();
        try {
            applyDeltaLevels(bids, bidUpdates);
            applyDeltaLevels(asks, askUpdates);

            this.lastUpdateId = updateId;
            this.lastSeqId = seqId;
            this.lastUpdateTime = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get current orderbook snapshot as lists of OrderBookLevel.
     */
    public Snapshot getSnapshot() {
        lock.readLock().lock();
        try {
            List<OrderBookLevel> bidList = new ArrayList<>(bids.size());
            for (var entry : bids.entrySet()) {
                bidList.add(new OrderBookLevel(entry.getKey(), entry.getValue()));
            }

            List<OrderBookLevel> askList = new ArrayList<>(asks.size());
            for (var entry : asks.entrySet()) {
                askList.add(new OrderBookLevel(entry.getKey(), entry.getValue()));
            }

            return new Snapshot(bidList, askList);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get snapshot with optional quantity multiplier (for futures contract conversion).
     */
    public Snapshot getSnapshot(BigDecimal quantityMultiplier) {
        if (quantityMultiplier == null || quantityMultiplier.compareTo(BigDecimal.ONE) == 0) {
            return getSnapshot();
        }

        lock.readLock().lock();
        try {
            List<OrderBookLevel> bidList = new ArrayList<>(bids.size());
            for (var entry : bids.entrySet()) {
                bidList.add(new OrderBookLevel(entry.getKey(), entry.getValue().multiply(quantityMultiplier)));
            }

            List<OrderBookLevel> askList = new ArrayList<>(asks.size());
            for (var entry : asks.entrySet()) {
                askList.add(new OrderBookLevel(entry.getKey(), entry.getValue().multiply(quantityMultiplier)));
            }

            return new Snapshot(bidList, askList);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reset the orderbook (e.g. on gap detection).
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            bids.clear();
            asks.clear();
            lastUpdateId = 0;
            lastSeqId = 0;
            initialized = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getBidCount() {
        lock.readLock().lock();
        try {
            return bids.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getAskCount() {
        lock.readLock().lock();
        try {
            return asks.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void applyLevels(TreeMap<BigDecimal, BigDecimal> side, List<List<String>> levels) {
        if (levels == null) return;
        for (List<String> level : levels) {
            BigDecimal price = new BigDecimal(level.get(0));
            BigDecimal qty = new BigDecimal(level.get(1));
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                side.put(price, qty);
            }
        }
    }

    private void applyDeltaLevels(TreeMap<BigDecimal, BigDecimal> side, List<List<String>> updates) {
        if (updates == null) return;
        for (List<String> update : updates) {
            BigDecimal price = new BigDecimal(update.get(0));
            BigDecimal qty = new BigDecimal(update.get(1));
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                side.remove(price);
            } else {
                side.put(price, qty);
            }
        }
    }

    public record Snapshot(List<OrderBookLevel> bids, List<OrderBookLevel> asks) {}
}
