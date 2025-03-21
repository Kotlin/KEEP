package org.sample;

import org.openjdk.jmh.annotations.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Fork(1) // -f 1
@Warmup(iterations = 4, time = 2) // -wi 4 -w 1
@Measurement(time = 2) // -r 1
@OutputTimeUnit(TimeUnit.MILLISECONDS) // -tu us
public class MyBenchmark {

    String x = "foo";

    @Benchmark
    public Object _005_listOf() {
        return listOf_vararg(x, x, x, x, x);
    }

    @Benchmark
    public Object _005_listOf_manual() {
        ArrayList<String> result = new ArrayList<>(5);
        result.add(x);result.add(x);result.add(x);result.add(x);result.add(x);
        return result;
    }

    @Benchmark
    public Object _005_setOf() {
        return setOf_vararg(x, x, x, x, x);
    }

    @Benchmark
    public Object _005_setOf_manual() {
        Set<String> set = new LinkedHashSet<>(mapCapacity(5));
        set.add(x);set.add(x);set.add(x);set.add(x);set.add(x);
        return set;
    }

    @Benchmark
    public Object _005_mapOf_manual() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>(mapCapacity(5));
        map.put(x, x);map.put(x, x);map.put(x, x);map.put(x, x);map.put(x, x);
        return map;
    }

    @Benchmark
    public Object _005_mapOf() {
        return mapOf_varargOfPair(Map.entry(x, x), Map.entry(x, x), Map.entry(x, x), Map.entry(x, x), Map.entry(x, x));
    }

    @Benchmark
    public Object _005_mapOf_separateArrays_avoidBoxing() {
        return mapOf_separateArrays(new String[]{x, x, x, x, x}, new String[]{x, x, x, x, x});
    }

    @Benchmark
    public Object _005_stdlib_listOf_kotlin() {
        return kotlin.collections.CollectionsKt.listOf(x, x, x, x, x);
    }

    @Benchmark
    public Object _005_stdlib_listOf_java() {
        return java.util.List.of(x, x, x, x, x);
    }

    @Benchmark
    public Object _011_listOf_kotlin() {
        return kotlin.collections.CollectionsKt.listOf(x, x, x, x, x, x, x, x, x, x, x);
    }

    @Benchmark
    public Object _011_listOf() {
        return listOf_vararg(x, x, x, x, x, x, x, x, x, x, x);
    }

    @Benchmark
    public Object _011_listOf_java() {
        return java.util.List.of(x, x, x, x, x, x, x, x, x, x, x);
    }

    static <K, V> Map<K, V> mapOf_separateArrays(K[] keys, V[] values) {
        if (keys.length != values.length) throw new IllegalArgumentException("Different sizes");
        LinkedHashMap<K, V> map = new LinkedHashMap<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
    }

    @SafeVarargs static <K, V> Map<K, V> mapOf_varargOfPair(Map.Entry<K, V>... entries) {
        if (entries.length == 0) return Collections.emptyMap();
        LinkedHashMap<K, V> map = new LinkedHashMap<>(mapCapacity(entries.length));
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    static <T> Set<T> setOf_vararg(T... t) {
        int len = t.length;
        if (len == 0) {
            return Collections.emptySet();
        } else if (len == 1) {
            return Collections.singleton(t[0]);
        } else {
            Set<T> set = new LinkedHashSet<>(mapCapacity(len));
            for (int i = 0; i < t.length; i++) set.add(t[i]);
            return set;
        }
    }

    static int mapCapacity(int expectedSize) {
        if (expectedSize < 0) return expectedSize;
        if (expectedSize < 3) return expectedSize + 1;
        if (expectedSize < INT_MAX_POWER_OF_TWO) {
            // This is the calculation used in JDK8 to resize when a putAll
            // happens; it seems to be the most conservative calculation we
            // can make.  0.75 is the default load factor.
            return (int) ((float) expectedSize / 0.75F + 1.0F);
        }
        return Integer.MAX_VALUE; // any large value
    }

    private static int INT_MAX_POWER_OF_TWO = 1 << (32 - 2);

    static <T> List<T> listOf_vararg(T... t) {
        int len = t.length;
        if (len == 0) {
            return Collections.emptyList();
        } else if (len == 1) {
            return Collections.singletonList(t[0]);
        } else {
            return Arrays.asList(t);
        }
    }

    static <T> List<T> listOf_empty() {
        return Collections.emptyList();
    }

    static <T> List<T> listOf_single(T t) {
        return Collections.singletonList(t);
    }

    static <T> List<T> mutableListOf_single(T t) {
        List<T> result = new ArrayList<>(1);
        result.add(t);
        return result;
    }

    static <T> List<T> mutableListOf_vararg(T... t) {
        if (t.length == 0) return new ArrayList<>();
        else return new ArrayList<>(new ArrayAsCollection(t));
    }

    private static class ArrayAsCollection<T> implements Collection<T> {
        private T[] arr;
        ArrayAsCollection(T[] arr) {this.arr = arr;}
        @Override public Object[] toArray() { return arr; }

        @Override public int size() {throw new IllegalStateException();}
        @Override public boolean isEmpty() {throw new IllegalStateException();}
        @Override public boolean contains(Object o) {throw new IllegalStateException();}
        @Override public Iterator<T> iterator() {throw new IllegalStateException();}
        @Override public <T1> T1[] toArray(T1[] a) {throw new IllegalStateException();}
        @Override public boolean add(T t) {throw new IllegalStateException();}
        @Override public boolean remove(Object o) {throw new IllegalStateException();}
        @Override public boolean containsAll(Collection<?> c) {throw new IllegalStateException();}
        @Override public boolean addAll(Collection<? extends T> c) {throw new IllegalStateException();}
        @Override public boolean removeAll(Collection<?> c) {throw new IllegalStateException();}
        @Override public boolean retainAll(Collection<?> c) {throw new IllegalStateException();}
        @Override public void clear() {throw new IllegalStateException();}
        @Override public boolean equals(Object o) {throw new IllegalStateException();}
        @Override public int hashCode() {throw new IllegalStateException();}
    }
}
