package ru.ricnorr.benchmarks;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.FileUtils;
import org.ejml.simple.SimpleMatrix;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import ru.ricnorr.numa.locks.mcs.MCSLock;
import ru.ricnorr.numa.locks.mcs.MCSYieldLock;
import ru.ricnorr.numa.locks.mcs.TestAndSetLock;
import ru.ricnorr.numa.locks.mcs.TestAndSetYieldLock;
import ru.ricnorr.numa.locks.mcs.TestTestAndSetLock;
import ru.ricnorr.numa.locks.mcs.TestTestAndSetYieldLock;
import ru.ricnorr.numa.locks.mcs.TicketLock;
import ru.ricnorr.numa.locks.mcs.TicketYieldLock;

public class Main {
    private static final List<String> RESULTS_HEADERS = List.of("name", "lock", "threads", "latency", "throughput");

    private static Lock initLock(LockType lockType) {
        switch (lockType) {
            case REENTRANT -> {
                return new ReentrantLock();
            }
            case MCS -> {
                return new MCSLock();
            }
            case TEST_SET -> {
                return new TestAndSetLock();
            }
            case TEST_TEST_SET -> {
                return new TestTestAndSetLock();
            }
            case TICKET -> {
                return new TicketLock();
            }
            case MCS_YIELD -> {
                return new MCSYieldLock();
            }
            case TEST_SET_YIELD -> {
                return new TestAndSetYieldLock();
            }
            case TEST_TEST_SET_YIELD -> {
                return new TestTestAndSetYieldLock();
            }
            case TICKET_YIELD -> {
                return new TicketYieldLock();
            }
            default -> throw new BenchmarkException("Can't init lockType " + lockType.name());
        }
    }

    private static SimpleMatrix initMatrix(Random rand, int size) {
        return SimpleMatrix.random_DDRM(size, size, 0, Float.MAX_VALUE, rand);
    }

    private static Runnable createMatrixRunnable(Lock lock, MatrixBenchmarkParameters matrixParam) {
        Random random = new Random();
        SimpleMatrix beforeMatrixA = initMatrix(random, matrixParam.beforeSize);
        SimpleMatrix beforeMatrixB = initMatrix(random, matrixParam.beforeSize);

        SimpleMatrix inMatrixA = initMatrix(random, matrixParam.inSize);
        SimpleMatrix inMatrixB = initMatrix(random, matrixParam.inSize);

        SimpleMatrix afterMatrixA = initMatrix(random, matrixParam.afterSize);
        SimpleMatrix afterMatrixB = initMatrix(random, matrixParam.afterSize);

        return () -> {
            beforeMatrixA.mult(beforeMatrixB);
            lock.lock();
            inMatrixA.mult(inMatrixB);
            lock.unlock();
            afterMatrixA.mult(afterMatrixB);
        };
    }

    private static void writeResultsToCSVfile(String filename, List<BenchmarkResultsCsv> results) {
        try (FileWriter out = new FileWriter(filename)) {
            try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                printer.printRecord(RESULTS_HEADERS);
                results.forEach(it -> {
                    try {
                        printer.printRecord(it.name(), it.lock(), it.threads(), it.latency(), it.throughput());
                    } catch (IOException e) {
                        throw new BenchmarkException("Cannot write record to file with benchmarks results", e);
                    }
                });
            }
        } catch (IOException e) {
            throw new BenchmarkException("Cannot write to file with benchmarks results", e);
        }
    }

    private static List<BenchmarkParameters> fillBenchmarkParameters(
        List<Integer> threads,
        List<LockType> lockTypes,
        JSONArray array
    ) {
        List<BenchmarkParameters> paramList = new ArrayList<>();
        for (Object o : array) {
            JSONObject obj = (JSONObject) o;
            String name = (String) obj.get("name");
            for (int thread : threads) {
                for (LockType lockType : lockTypes) {
                    switch (name) {
                        case "matrix" -> {
                            int before = (int) ((long) obj.get("before"));
                            int in = (int) ((long) obj.get("in"));
                            int after = (int) ((long) obj.get("after"));
                            paramList.add(new MatrixBenchmarkParameters(thread, lockType, before, in, after));
                        }
                        default -> throw new BenchmarkException("Unsupported benchmark name");
                    }
                }
            }
        }
        return paramList;
    }

    private static List<Integer> autoThreadsInit() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        List<Integer> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int left = 1 << i;
            int right = 1 << (i + 1);
            int dist = (right - left) / 2;
            threads.add(left);
            if (dist != 0) {
                threads.add(left + dist);
            }
        }
        List<Integer> result = threads.stream().filter(it -> it < cpuCount).collect(Collectors.toList());
        result.add(cpuCount);
        result.add(cpuCount * 2);
        return result;
    }

    public static void main(String[] args) {

         // Read benchmark parameters
        String s;
        try {
            s = FileUtils.readFileToString(new File("settings/settings.json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BenchmarkException("Cannot read input file", e);
        }
        JSONObject obj = (JSONObject) JSONValue.parse(s);
        int warmupIterations = (int) ((long) obj.get("warmupIterations"));
        int iterations = (int) ((long) obj.get("iterations"));
        int durationInMillis = (int) (long) obj.get("durationInMillis");
        long latencyPercentile = ((long) obj.get("latencyPercentile"));
        System.out.println(String.format(
            "Init benchmark params: warmupIterations=%d, iterations=%d,durationInMillis=%d,latencyPercentile=%d",
            warmupIterations,
            iterations,
            durationInMillis,
            latencyPercentile
        ));
        BenchmarkRunner benchmarkRunner =
            new BenchmarkRunner(durationInMillis, warmupIterations, iterations, latencyPercentile);

        JSONArray array = (JSONArray) obj.get("threads");
        List<Integer> threadsList = new ArrayList<>();
        if (array != null) {
            for (Object value : array) {
                threadsList.add((int) ((long) value));
            }
        } else {
            threadsList = autoThreadsInit();
        }

        array = (JSONArray) obj.get("locks");
        List<LockType> locksType = new ArrayList<>();
        for (Object value : array) {
            String lockType = (String) value;
            locksType.add(LockType.valueOf(lockType));
        }
        array = (JSONArray) obj.get("benches");
        List<BenchmarkParameters> benchmarkParametersList = fillBenchmarkParameters(threadsList, locksType, array);

        // Run benches and collect results
        List<BenchmarkResultsCsv> resultCsv = new ArrayList<>();
        for (BenchmarkParameters param : benchmarkParametersList) {
            Lock lock = initLock(param.lockType);
            Runnable benchRunnable;
            if (param instanceof MatrixBenchmarkParameters matrixParam) {
                benchRunnable = createMatrixRunnable(lock, matrixParam);
            } else {
                throw new BenchmarkException("Cannot init runnable for parameter");
            }
            System.out
                .printf("Run bench,name=%s,threads=%d,lock=%s%n", param.getBenchmarkName(), param.threads, param.lockType.name());
            BenchmarkResult result = benchmarkRunner.benchmark(param.threads, benchRunnable);
            System.out.println("Bench ended");
            resultCsv.add(new BenchmarkResultsCsv(
                param.getBenchmarkName(),
                param.lockType.name(),
                param.threads,
                result.throughput(),
                result.latency()
            ));
        }

        // Print results to file
        writeResultsToCSVfile("results/benchmark_results.csv", resultCsv);
    }
}
