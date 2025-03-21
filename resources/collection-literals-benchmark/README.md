# Kotlin listOf performance benchmark

## How to run

```
mvn clean verify && java -jar target/benchmarks.jar
```

## Results

```
# JMH version: 1.37
# VM version: JDK 17.0.10, OpenJDK 64-Bit Server VM, 17.0.10+7-LTS
# VM invoker: /Users/Nikita.Bobko/.sdkman/candidates/java/17.0.10-amzn/bin/java
# VM options: <none>
# Blackhole mode: compiler (auto-detected, use -Djmh.blackhole.autoDetect=false to disable)
# Warmup: 4 iterations, 2 s each
# Measurement: 5 iterations, 2 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Throughput, ops/time

Benchmark                                           Mode  Cnt       Score       Error   Units
MyBenchmark._005_listOf                            thrpt    5  344460,019 ± 17760,336  ops/ms
MyBenchmark._005_listOf_manual                     thrpt    5  135482,178 ±  3514,682  ops/ms
MyBenchmark._005_mapOf                             thrpt    5   25164,393 ±   267,319  ops/ms
MyBenchmark._005_mapOf_manual                      thrpt    5   51303,788 ±   879,191  ops/ms
MyBenchmark._005_mapOf_separateArrays_avoidBoxing  thrpt    5   38183,352 ±   895,615  ops/ms
MyBenchmark._005_setOf                             thrpt    5   40686,152 ±   980,106  ops/ms
MyBenchmark._005_setOf_manual                      thrpt    5   49328,685 ±   505,991  ops/ms
MyBenchmark._005_stdlib_listOf_java                thrpt    5  360196,164 ±  8817,743  ops/ms
MyBenchmark._005_stdlib_listOf_kotlin              thrpt    5  342669,622 ±  3266,789  ops/ms
MyBenchmark._011_listOf                            thrpt    5  285215,340 ±  4678,364  ops/ms
MyBenchmark._011_listOf_java                       thrpt    5   90536,739 ±   666,179  ops/ms
MyBenchmark._011_listOf_kotlin                     thrpt    5  285114,627 ±  5262,411  ops/ms
```

## Interpretation

[collection-literals.md](../../proposals/collection-literals.md)
