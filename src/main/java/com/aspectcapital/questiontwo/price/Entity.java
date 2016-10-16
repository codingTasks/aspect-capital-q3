package com.aspectcapital.questiontwo.price;

import org.apache.log4j.Logger;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Entity {
    private final static Logger logger = Logger.getLogger(Entity.class);

    private final String name;
    private BigDecimal price;
    private BigDecimal nextPriceToProcess;
    private AtomicBoolean inProcessing = new AtomicBoolean();
    private ThreadLocal<BigDecimal> lastPriceRead = new ThreadLocal<>();
    private BigDecimal nextPriceToReturn;
    private ReentrantLock lock = new ReentrantLock();
    private Condition newPricePut = lock.newCondition();

    Entity(String name) {
        this.name = name;
    }

    BigDecimal getLastPriceRead() {
        return lastPriceRead.get();
    }

    void setLastPriceRead(BigDecimal lastPriceRead) {
        synchronized (lock) {
            this.lastPriceRead.set(lastPriceRead);
            logger.debug(String.format("[LAST PRICE READ SET] %s", this));
        }
    }

    public BigDecimal getPrice() {
        BigDecimal toReturn;

        synchronized (lock) {
            toReturn = price;
            setLastPriceRead(toReturn);
        }

        return toReturn;
    }

    public void setPrice(BigDecimal price) {
        synchronized (lock) {
            this.price = price;
            onNewPrice();
        }
    }

    BigDecimal getNextPriceToProcess() {
        return nextPriceToProcess;
    }

    void setNextPriceToProcess(BigDecimal next) {
        nextPriceToProcess = next;
    }

    @Override
    public String toString() {
        return "Entity{" +
                "name='" + name + '\'' +
                ", price=" + price +
                ", nextPriceToProcess=" + nextPriceToProcess +
                ", inProcessing=" + inProcessing +
                ", lastPriceRead=" + lastPriceRead.get() +
                ", nextPriceToReturn=" + nextPriceToReturn +
                '}';
    }

    boolean isInProcessing() {
        return inProcessing.get();
    }

    void setInProcessing(boolean inProcessing) {
        synchronized (lock) {
            this.inProcessing.set(inProcessing);
            logger.debug(String.format("[IN PROCESSING]: %s, for: %s", inProcessing, this));
        }
    }

    private void onNewPrice() {
        lock.lock();
        try {
            nextPriceToReturn = price;
            logger.debug(String.format("[SIGNAL ALL] %s ", this));
            newPricePut.signalAll();
        } finally {
            lock.unlock();
        }
    }

    BigDecimal getNewPrice() throws InterruptedException {
        BigDecimal newPrice = price;

        lock.lock();
        try {
            while (!hasPriceChanged()) {
                newPricePut.await();
                newPrice = nextPriceToReturn;
            }
        } finally {
            lock.unlock();
        }

        setLastPriceRead(newPrice);
        logger.debug(String.format("[NEXT PRICE] %s, price: %f", this, newPrice));

        return newPrice;
    }

    boolean hasPriceChanged() {
        return nullSafeIsEqual(price, lastPriceRead.get());
    }

    public boolean hasPriceToProcess() {
        return nullSafeIsEqual(price, nextPriceToProcess);
    }

    private boolean nullSafeIsEqual(BigDecimal price1, BigDecimal price2) {
        boolean priceChanged = true;

        if(price1 != null) {
            if(price2 != null)
                priceChanged = price1.compareTo(price2) != 0;
        }
        else
            priceChanged = price2 != null;

        return priceChanged;
    }
}
