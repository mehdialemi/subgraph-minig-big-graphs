package ir.ac.sbu.graph.ktruss.multicore;

import ir.ac.sbu.graph.ktruss.sequential.Edge;
import scala.Tuple2;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static ir.ac.sbu.graph.ktruss.multicore.MultiCoreUtils.createBuckets;

public class TriangleParallelExecutor {
    public static final int BUCKET_LEN = 10000;
    public static final Tuple2<Integer, Integer> INVALID_TUPLE2 = new Tuple2<>(-1, -1);

    public static Tuple2<int[][], Set<Integer>[]> findEdgeTriangles(final List<Edge> edges, final int threads, ForkJoinPool forkJoinPool)
        throws Exception {
        // find max vertex Id in parallel
        long t1 = System.currentTimeMillis();
        final List<Tuple2<Integer, Integer>> edgeBuckets = createBuckets(threads, edges.size());
        int max = forkJoinPool.submit(() -> {
            return edgeBuckets.parallelStream().mapToInt(bucket -> {
                int maxV = 0;
                for (int i = bucket._1; i < bucket._2; i++) {
                    int maxE = Math.max(edges.get(i).v1, edges.get(i).v2);
                    if (maxE > maxV)
                        maxV = maxE;
                }
                return maxV;
            }).reduce((max1, max2) -> Math.max(max1, max2)).getAsInt();
        }).get();

        long t2 = System.currentTimeMillis();
        System.out.println("Finding max in " + (t2 - t1) + " ms");

        if (max == -1)
            throw new Exception("Problem in max finding");

        // construct deg array
        AtomicInteger[] degArray = new AtomicInteger[max + 1];
        forkJoinPool.submit(() -> IntStream.range(0, degArray.length).parallel().forEach(i -> degArray[i] = new AtomicInteger(0))).get();

        long t3 = System.currentTimeMillis();
        System.out.println("Construct degArray (AtomicInteger) in " + (t3 - t2) + " ms");

        // Construct degree array such that vertexId is the index of the array in parallel
        forkJoinPool.submit(() -> edges.parallelStream().forEach(e -> {
            degArray[e.v1].incrementAndGet();
            degArray[e.v2].incrementAndGet();
        })).get();

        long t4 = System.currentTimeMillis();
        System.out.println("Fill degArray in " + (t4 - t3) + " ms");

        // Fill and sort vertices array.
        final int[] vertices = sort(degArray, threads);
        final int[] rvertices = new int[vertices.length];
        forkJoinPool.submit(() -> IntStream.range(0, threads).forEach(index -> {
            for (int i = index; i < vertices.length; i += threads)
                rvertices[vertices[i]] = i;
        })).get();

        long t5 = System.currentTimeMillis();
        System.out.println("Sort degArray in " + (t5 - t4) + " ms");

        // Construct neighborhood
        final List<Long>[] neighbors = new List[vertices.length];
        forkJoinPool.submit(() -> Arrays.stream(vertices).parallel().forEach(v -> neighbors[v] = new ArrayList<>())).get();

        long t6 = System.currentTimeMillis();
        System.out.println("Construct neighbors in " + (t6 - t5) + " ms");

        // Fill neighbors array
        forkJoinPool.submit(() -> edgeBuckets.parallelStream().forEach(bucket -> {
            for (int i = bucket._1; i < bucket._2; i++) {
                Edge e = edges.get(i);
                if (rvertices[e.v2] >= rvertices[e.v1]) {
                    synchronized (degArray[e.v1]) {
                        neighbors[e.v1].add((long) e.v2 << 32 | i & 0xFFFFFFFFL);
                    }
                } else {
                    synchronized (degArray[e.v2]) {
                        neighbors[e.v2].add((long) e.v1 << 32 | i & 0xFFFFFFFFL);
                    }
                }
            }
        })).get();

        //TODO sort neighbor list???

        long t7 = System.currentTimeMillis();
        System.out.println("Fill neighbors in " + (t7 - t6) + " ms");
        long t8 = System.currentTimeMillis();

        long t9 = System.currentTimeMillis();
        System.out.println("Ready to triangle in " + (t9 - t8) + " ms with " + edgeBuckets.size() + " edgeBuckets");

        final int offset = forkJoinPool.submit(() -> (int) Arrays.stream(degArray).parallel().filter(d -> d.get() < 2).count()).get();
        final int length = vertices.length;
        final AtomicInteger blockOffset = new AtomicInteger(offset);
        final AtomicInteger triangleOffset = new AtomicInteger(0);

        Stream<Tuple2<Map<Integer, int[]>, Map<Integer, List<Integer>>>> results = forkJoinPool.submit(() -> {
            return IntStream.range(0, threads).parallel().mapToObj(index -> {
                Map<Integer, List<Integer>> localEdgeTriangles = new HashMap<>();
                Map<Integer, int[]> localTriangles = new HashMap<>();
                int triangleNum = triangleOffset.getAndAdd(BUCKET_LEN);
                int i = 0;
                while (i < length) {
                    int start = blockOffset.getAndAdd(BUCKET_LEN);
                    for (i = start; i < start + BUCKET_LEN && i < length; i++) {
                        int u = vertices[i];   // get current vertex id as u

                        // TODO use bitset
                        // construct a map of nodes to their edges for u
                        final List<Long> uNeighbors = neighbors[u];
                        Map<Integer, Integer> unEdges = new HashMap<>(uNeighbors.size());
                        for (int ni = 0; ni < uNeighbors.size(); ni++) {
                            long ve = uNeighbors.get(ni);
                            int v = (int) (ve >> 32);
                            int e = (int) ve;
                            unEdges.put(v, e);
                        }

                        // iterate over neighbors of u using edge info
                        for (int j = 0; j < uNeighbors.size(); j++) {
                            long ve = uNeighbors.get(j);
                            int v = (int) (ve >> 32);
                            int uv = (int) ve;

                            // iterate over neighbors of v
                            List<Long> vNeighbors = neighbors[v];
                            for (int k = 0; k < vNeighbors.size(); k++) {
                                long we = vNeighbors.get(k);
                                int w = (int) (we >> 32);
                                int vw = (int) we;

                                Integer uw = unEdges.get(w);
                                if (uw != null) {
                                    List<Integer> list = localEdgeTriangles.get(uv);
                                    if (list == null) {
                                        list = new ArrayList<>();
                                        localEdgeTriangles.put(uv, list);
                                    }
                                    list.add(triangleNum);

                                    list = localEdgeTriangles.get(uw);
                                    if (list == null) {
                                        list = new ArrayList<>();
                                        localEdgeTriangles.put(uw, list);
                                    }
                                    list.add(triangleNum);

                                    list = localEdgeTriangles.get(vw);
                                    if (list == null) {
                                        list = new ArrayList<>();
                                        localEdgeTriangles.put(vw, list);
                                    }
                                    list.add(triangleNum);

                                    localTriangles.put(triangleNum, new int[]{vw, uv, uw});
                                    triangleNum++;
                                    if (triangleNum % BUCKET_LEN == 0)
                                        triangleNum = triangleOffset.getAndAdd(BUCKET_LEN);
                                }
                            }
                        }
                    }
                }
                return new Tuple2<>(localTriangles, localEdgeTriangles);
            });
        }).get();

        final HashSet<Integer>[] edgeTriangles = new HashSet[edges.size()];
        final List<Map<Integer, int[]>> localTrianglesList = new ArrayList<>(threads);
        final AtomicInteger triangleCount = new AtomicInteger(0);
        forkJoinPool.submit(() -> {
            results.forEach(result -> {
                Set<Map.Entry<Integer, List<Integer>>> localEdgeTriangles = result._2.entrySet();
                localEdgeTriangles.parallelStream().forEach(entry -> {
                    if (edgeTriangles[entry.getKey()] == null)
                        edgeTriangles[entry.getKey()] = new HashSet<>();
                    edgeTriangles[entry.getKey()].addAll(entry.getValue());
                });
                localTrianglesList.add(result._1);
                triangleCount.addAndGet(result._1.size());
            });
        }).get();

        System.out.println("triangle count: " + triangleCount.get());
        int[][] triangles = new int[triangleCount.get() + BUCKET_LEN * threads][];
        forkJoinPool.submit(() ->
            localTrianglesList.parallelStream().forEach(localTriangles ->
                localTriangles.entrySet().forEach(entry ->
                    triangles[entry.getKey()] = entry.getValue())
            )).get();

        long t10 = System.currentTimeMillis();
        System.out.println("Triangle finished in " + (t10 - t9));
        return new Tuple2<>(triangles, edgeTriangles);
    }

    public static int[] sort(AtomicInteger[] degArray, int threads, ForkJoinPool forkJoinPool) throws Exception {
        List<Tuple2<Integer, Integer>> buckets = createBuckets(threads, degArray.length);
        Tuple2<Integer, Integer> minMaxLen = forkJoinPool.submit(() ->
            buckets.parallelStream().map(bucket -> {
                int min = degArray[bucket._1].get();
                int max = degArray[bucket._1].get();
                for (int i = bucket._1 + 1; i < bucket._2; i++) {
                    int deg = degArray[i].get();
                    if (deg == 0)
                        continue;
                    if (deg < min) {
                        min = deg;
                    } else if (deg > max)
                        max = deg;
                }
                return new Tuple2<>(min, max);
            }).reduce((r1, r2) -> new Tuple2<>(Math.min(r1._1(), r2._1()), Math.max(r1._2(), r2._2())))
                .orElse(INVALID_TUPLE2)
        ).get();

        if (minMaxLen == INVALID_TUPLE2)
            throw new Exception("Invalid tuple");

        int min = minMaxLen._1();
        int max = minMaxLen._2();

        // init the frequencies
        AtomicInteger[] counts = new AtomicInteger[max - min + 1];
        for (int i = 0; i < counts.length; i++)
            counts[i] = new AtomicInteger(0);
        forkJoinPool.submit(() -> buckets.parallelStream().forEach(bucket ->
            IntStream.range(bucket._1, bucket._2).forEach(i ->
                counts[degArray[i].get() - min].incrementAndGet()))
        ).get();

        counts[0].decrementAndGet();
        for (int i = 1; i < counts.length; i++) {
            counts[i].addAndGet(counts[i - 1].get());
        }

        int[] vertices = new int[degArray.length];
        for (int i = vertices.length - 1; i >= 0; i--) {
            int index = counts[degArray[i].get() - min].getAndDecrement();
            vertices[index] = i;
        }
        return vertices;
    }
}
