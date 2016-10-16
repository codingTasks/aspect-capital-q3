package com.aspectcapital.questiontwo.price;

import com.aspectcapital.questiontwo.price.processor.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.math.BigDecimal;
import java.util.Random;
import java.util.concurrent.*;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

public class PriceHolderTest {
    private static final int ALL_TEST_TIMEOUT_SECONDS = 10;
    private static final String ENTITY_NAME_A = "a";
    private static final String ENTITY_NAME_B = "b";
    private static final long BLOCKING_TEST_TIMEOUT = 1000;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(ALL_TEST_TIMEOUT_SECONDS);
    private DelayingPriceProcessor priceProcessor;
    private PriceHolder priceHolder;
    private ExecutorService putPriceExecutorService;
    private ExecutorService getPriceExecutorService;
    private final Random random = new Random();

    @After
    public void tearDown() throws Exception {
        if (putPriceExecutorService != null)
            putPriceExecutorService.shutdown();
        if (getPriceExecutorService != null)
            getPriceExecutorService.shutdown();
    }

    @Test
    public void shouldSetLastPriceRedOnGetPriceInvoked() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        priceHolder = new PriceHolder(new CountingAllInvocationsPriceProcessor(0, latch));
        Entity entity = priceHolder.getOrCreateEntity(ENTITY_NAME_A);

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(10));
        latch.await();
        priceHolder.getPrice(ENTITY_NAME_A);

        assertThat(entity.getLastPriceRead(), is(equalTo(entity.getPrice())));
    }

    @Test
    public void nothingShouldHappenWhenStartProcessingCalledMultipleTimes() throws Exception {
        priceHolder = new PriceHolder(mock(RewritingPriceProcessor.class));

        priceHolder.startProcessing();
        priceHolder.startProcessing();
    }


    @Test
    public void shouldReturnLastPriceAvailableForOneEntity() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        BigDecimal lastPrice = new BigDecimal(12);
        priceHolder = new PriceHolder(new CountingOnePriceValuePriceProcessor(0, latch, lastPrice));

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(11));
        priceHolder.putPrice(ENTITY_NAME_A, lastPrice);
        latch.await();

        BigDecimal price = priceHolder.getPrice(ENTITY_NAME_A);
        assertThat(price, is(equalTo(lastPrice)));
    }

    @Test
    public void shouldReturnFalseWhenPriceHasNotChangedSinceLastRetrieval() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        BigDecimal lastPrice = new BigDecimal(10);
        priceHolder = new PriceHolder(new CountingOnePriceValuePriceProcessor(0, latch, lastPrice));

        priceHolder.putPrice(ENTITY_NAME_A, lastPrice);
        latch.await();
        priceHolder.getPrice(ENTITY_NAME_A);

        assertThat(priceHolder.hasPriceChanged(ENTITY_NAME_A), is(false));
    }

    @Test
    public void shouldReturnTrueWhenPriceHasChangedSinceLastRetrieval() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        BigDecimal lastPrice = new BigDecimal(11);
        priceHolder = new PriceHolder(new CountingOnePriceValuePriceProcessor(0, latch, lastPrice));

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(10));
        waitForPriceChange(ENTITY_NAME_A);
        priceHolder.getPrice(ENTITY_NAME_A);
        priceHolder.putPrice(ENTITY_NAME_A, lastPrice);
        latch.await();

        assertThat(priceHolder.hasPriceChanged(ENTITY_NAME_A), is(true));
    }

    @Test
    public void shouldReturnPriceWhenOnlyOnePriceWasPutForEachEntityByOneThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        BigDecimal lastPrice = new BigDecimal(11);
        priceHolder = new PriceHolder(new CountingOnePriceValuePriceProcessor(0, latch, lastPrice));

        priceHolder.putPrice(ENTITY_NAME_A, lastPrice);
        priceHolder.putPrice(ENTITY_NAME_B, lastPrice);
        latch.await();

        assertThat(priceHolder.getPrice(ENTITY_NAME_A), is(lastPrice));
        assertThat(priceHolder.getPrice(ENTITY_NAME_B), is(lastPrice));
    }

    @Test
    public void shouldReturnPriceWhenOnlyOnePriceWasPutForEachEntityByMultipleThreads() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        BigDecimal lastPrice = new BigDecimal(12);
        priceHolder = new PriceHolder(new CountingOnePriceValuePriceProcessor(0, latch, lastPrice));
        putPriceExecutorService = Executors.newFixedThreadPool(2);
        BigDecimal priceA1 = new BigDecimal(12);

        putPriceInSeparateThread(ENTITY_NAME_A, priceA1);
        putPriceInSeparateThread(ENTITY_NAME_B, lastPrice);
        latch.await();

        assertThat(priceHolder.getPrice(ENTITY_NAME_A), is(equalTo(priceA1)));
        assertThat(priceHolder.getPrice(ENTITY_NAME_B), is(equalTo(lastPrice)));
    }

    @Test
    public void shouldReturnNextPriceWhenAvailableAfterLastWaitCalled() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        priceHolder = new PriceHolder(new CountingAllInvocationsPriceProcessor(0, latch));
        BigDecimal price2 = new BigDecimal(11);
        BigDecimal price3 = new BigDecimal(12);

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(10));
        priceHolder.waitForNextPrice(ENTITY_NAME_A);
        priceHolder.putPrice(ENTITY_NAME_A, price2);
        priceHolder.putPrice(ENTITY_NAME_A, price3);
        latch.await();

        assertThat(priceHolder.waitForNextPrice(ENTITY_NAME_A), is(equalTo(price3)));
    }

    @Test
    public void shouldReturnNextPriceWhenAvailableAfterLastGetPriceCalled() throws Exception {
        priceHolder = new PriceHolder(new DelayingPriceProcessor(0));
        BigDecimal price2 = new BigDecimal(11);

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(10));
        waitForPriceChange(ENTITY_NAME_A);
        priceHolder.getPrice(ENTITY_NAME_A);
        priceHolder.putPrice(ENTITY_NAME_A, price2);
        waitForPriceChange(ENTITY_NAME_A);

        assertThat(priceHolder.waitForNextPrice(ENTITY_NAME_A), is(equalTo(price2)));
    }

    private void waitForPriceChange(String entityName) {
        while (!priceHolder.getOrCreateEntity(entityName).hasPriceChanged()) {
        }
    }

    @Test
    public void shouldBlockWhenNextPriceNotAvailable() throws Exception {
        priceHolder = new PriceHolder(new DelayingPriceProcessor(0));

        Thread nextPriceTaker = new Thread() {
            @Override
            public void run() {
                try {
                    priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(10));
                    waitForPriceChange(ENTITY_NAME_A);
                    priceHolder.waitForNextPrice(ENTITY_NAME_A);
                    fail();
                } catch (InterruptedException e) {
                }
            }
        };

        try {
            nextPriceTaker.start();
            Thread.sleep(BLOCKING_TEST_TIMEOUT);
            nextPriceTaker.interrupt();
            nextPriceTaker.join(BLOCKING_TEST_TIMEOUT);
            assertThat(nextPriceTaker.isAlive(), is(false));
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void shouldWaitForTheNextPriceWhenNotAvailableAndReturnOnceItsAvailable() throws Exception {
        priceHolder = new PriceHolder(new DelayingPriceProcessor(0));
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        BigDecimal price2 = new BigDecimal(11);

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(10));
        executorService.submit(() -> priceHolder.getPrice(ENTITY_NAME_A));

        Future<BigDecimal> nextPrice = executorService.submit(() -> priceHolder.waitForNextPrice(ENTITY_NAME_A));
        scheduledExecutorService.schedule(() -> priceHolder.putPrice(ENTITY_NAME_A, price2), 3, TimeUnit.SECONDS);

        long start = System.nanoTime();
        BigDecimal price = nextPrice.get();
        long duration = System.nanoTime() - start;

        assertThat(duration, greaterThan(TimeUnit.SECONDS.toNanos(3)));
        assertThat(duration, lessThan(TimeUnit.SECONDS.toNanos(4)));
        assertThat(price, is(equalTo(price2)));
    }

    @Test
    public void shouldWaitInTwoThreadsForTheNextPriceWhenNotAvailableAndReturnOnceItsAvailable() throws Exception {
        priceHolder = new PriceHolder(new DelayingPriceProcessor(0));
        ExecutorService executorService1 = Executors.newSingleThreadExecutor();
        ExecutorService executorService2 = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        BigDecimal price2 = new BigDecimal(11);

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(10));
        waitForPriceChange(ENTITY_NAME_A);
        executorService1.submit(() -> priceHolder.getPrice(ENTITY_NAME_A)).get();
        executorService2.submit(() -> priceHolder.getPrice(ENTITY_NAME_A)).get();

        Future<BigDecimal> nextPriceThread1 = executorService1.submit(() -> priceHolder.waitForNextPrice(ENTITY_NAME_A));
        Future<BigDecimal> nextPriceThread2 = executorService2.submit(() -> priceHolder.waitForNextPrice(ENTITY_NAME_A));
        scheduledExecutorService.schedule(() -> priceHolder.putPrice(ENTITY_NAME_A, price2), 3, TimeUnit.SECONDS);

        long start = System.nanoTime();
        BigDecimal priceThread1 = nextPriceThread1.get();
        BigDecimal priceThread2 = nextPriceThread2.get();
        long duration = System.nanoTime() - start;

        assertThat(duration, greaterThan(TimeUnit.MILLISECONDS.toNanos(3000)));
        assertThat(duration, lessThan(TimeUnit.MILLISECONDS.toNanos(3100)));
        assertThat(priceThread1, is(equalTo(price2)));
        assertThat(priceThread2, is(equalTo(price2)));
    }

    @Test
    public void shouldReturnFirstPriceAfterInvokingWaitForNextPrice() throws Exception {
        priceHolder = new PriceHolder(new DelayingPriceProcessor(0));
        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        BigDecimal nextPrice = new BigDecimal(10);

        priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(9));
        waitForPriceChange(ENTITY_NAME_A);
        priceHolder.getPrice(ENTITY_NAME_A);

        executorService.schedule(() -> priceHolder.putPrice(ENTITY_NAME_A, nextPrice), 1000, TimeUnit.MILLISECONDS);
        executorService.schedule(() -> priceHolder.putPrice(ENTITY_NAME_A, new BigDecimal(11)), 1000, TimeUnit.MILLISECONDS);
        BigDecimal retrievedNextPrice = priceHolder.waitForNextPrice(ENTITY_NAME_A);

        assertThat(retrievedNextPrice, is(equalTo(nextPrice)));
    }

    @Test
    public void shouldGetLastPriceForMultipleEntitiesAndMultiplePrices() throws InterruptedException, ExecutionException {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfEntities(100)
                .setNumberOfPrices(100)
                .build();

        runLoadTest(tc);
    }

    @Test
    public void shouldGetLastPriceForOneEntityAndMultiplePrices() throws InterruptedException, ExecutionException {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfPrices(100)
                .build();

        runLoadTest(tc);
    }

    @Test
    public void shouldGetLastPriceForMultipleEntitiesAndOnePrice() throws InterruptedException, ExecutionException {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfEntities(100)
                .build();

        runLoadTest(tc);
    }

    @Test
    public void shouldProcessTwoPricesWhenOneEntityManyPricesPutAndLongProcessingTimeOnePutThread() throws Exception {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfPrices(100)
                .setProcessSleepMilliseconds(3000)
                .build();

        runLoadTest(tc);
        verify(priceProcessor, times(2)).process(any());
    }

    @Test
    public void shouldProcessTwoPricesWhenOneEntityManyPricesPutAndLongProcessingTimeMultiplePutThread() throws Exception {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfPrices(100)
                .setProcessSleepMilliseconds(3000)
                .setNumberOfPutThreads(10)
                .build();

        runLoadTest(tc);
        verify(priceProcessor, times(2)).process(any());
    }

    @Test
    public void shouldProcessEveryPriceWhenOneEntityManyPricesPutDelayedMultiplePutThread() throws Exception {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfPrices(100)
                .setPutSleepMilliseconds(40)
                .setNumberOfPutThreads(10)
                .build();

        runLoadTest(tc);

        verify(priceProcessor, times(tc.getNumberOfPrices())).process(any());
    }

    @Test
    public void shouldProcessEveryPriceWhenMultipleEntitiesManyPricesPutDelayedMultiplePutThread() throws Exception {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfEntities(10)
                .setNumberOfPrices(10)
                .setPutSleepMilliseconds(50)
                .setNumberOfPutThreads(10)
                .build();

        runLoadTest(tc);
        verify(priceProcessor, times(tc.getNumberOfPrices() * tc.getNumberOfEntities())).process(any());
    }

    @Test
    public void shouldBeAbleToProcessPricesForMultipleEntitiesAndMultiplePutAndGetThreads() throws Exception {
        TestConfiguration tc = new TestConfiguration.Builder()
                .setNumberOfEntities(100)
                .setNumberOfPutThreads(100)
                .setNumberOfGetThreads(100)
                .build();

        runLoadTest(tc);
    }

    private void runLoadTest(TestConfiguration tc) throws InterruptedException, ExecutionException {
        CountDownLatch latch = new CountDownLatch(tc.getNumberOfEntities());
        initialize(tc, latch);

        for (int entityName = 0, price = 1; entityName < tc.getNumberOfPrices() * tc.getNumberOfEntities(); entityName++) {
            putPrice(tc.getNumberOfEntities(), entityName, price);
            getPriceInSeparateThread(String.valueOf(random.nextInt(tc.getNumberOfEntities())));

            price = incrementPriceIfNeeded(tc, entityName, price);
            Thread.sleep(tc.getPutSleepMilliseconds());
        }

        latch.await();

        verifyAllPricesWerePut(tc);
        if (canValidateLastPrices(tc.getNumberOfPrices(), tc.getNumberOfPutThreads())) {
            validateLastEntitiesPrices(tc.getNumberOfEntities(), tc.getNumberOfPrices());
        }
    }

    private int incrementPriceIfNeeded(TestConfiguration tc, int i, int price) {
        if (i % tc.getNumberOfEntities() == (tc.getNumberOfEntities() - 1))
            price++;
        return price;
    }

    private void verifyAllPricesWerePut(TestConfiguration tc) {
        verify(priceHolder, times(tc.getNumberOfEntities() * tc.getNumberOfPrices())).putPrice(anyString(), any());
    }

    private void initialize(TestConfiguration tc, CountDownLatch latch) {
        BigDecimal lastPrice = BigDecimal.valueOf(tc.getNumberOfPrices());

        priceProcessor = spy(TestPriceProcessorFactory.getPriceProcessor(tc.getNumberOfPutThreads(), tc.getProcessSleepMilliseconds(), latch, lastPrice));
        priceHolder = spy(new PriceHolder(priceProcessor));

        putPriceExecutorService = Executors.newFixedThreadPool(tc.getNumberOfPutThreads());
        getPriceExecutorService = Executors.newFixedThreadPool(tc.getNumberOfGetThreads());
    }

    private boolean canValidateLastPrices(int numberOfPrices, int numberOfPutThreads) {
        return numberOfPutThreads == 1 || numberOfPrices == 1;
    }

    private void validateLastEntitiesPrices(int numberOfEntities, int numberOfPrices) throws InterruptedException, ExecutionException {
        for (int entityName = 1; entityName <= numberOfEntities; entityName++) {
            int finalI = entityName;
            Future<BigDecimal> price = getPriceInSeparateThread(String.valueOf(finalI));
            assertThat("Entity name: " + String.valueOf(entityName), price.get().intValue(), is(numberOfPrices));
        }
    }

    private void putPrice(int numberOfEntities, int i, int j) {
        String entityName = String.valueOf(i % numberOfEntities + 1);
        BigDecimal price = BigDecimal.valueOf(j);

        putPriceInSeparateThread(entityName, price);
    }

    private Future<?> putPriceInSeparateThread(String entityName, BigDecimal price) {
        return putPriceExecutorService.submit(() -> priceHolder.putPrice(entityName, price));
    }

    private Future<BigDecimal> getPriceInSeparateThread(String entityName) {
        return getPriceExecutorService.submit(() -> priceHolder.getPrice(entityName));
    }
}
