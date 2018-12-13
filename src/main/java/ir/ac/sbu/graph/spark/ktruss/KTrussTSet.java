package ir.ac.sbu.graph.spark.ktruss;

import ir.ac.sbu.graph.spark.ArgumentReader;
import ir.ac.sbu.graph.spark.EdgeLoader;
import ir.ac.sbu.graph.spark.NeighborList;
import ir.ac.sbu.graph.spark.SparkApp;
import ir.ac.sbu.graph.spark.kcore.KCore;
import ir.ac.sbu.graph.spark.triangle.Triangle;
import ir.ac.sbu.graph.types.Edge;
import ir.ac.sbu.graph.types.VSign;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static ir.ac.sbu.graph.utils.Log.log;

/**
 * Generate ktruss in 2 phase:
 * 1: Create triangle set using {@link Triangle}
 * 2: Iteratively, prune invalid edges which have not enough support
 */
public class KTrussTSet extends SparkApp {
    private static final int INVALID = -1;
    private static final int META_LEN = 4;
    private static final byte W_UVW = (byte) 0;
    private static final byte V_UVW = (byte) 1;
    private static final byte U_UVW = (byte) 2;
    public static final int CHECKPOINT_ITERATION = 50;

    private final NeighborList neighborList;
    private final int k;
    private KTrussConf ktConf;

    public KTrussTSet(NeighborList neighborList, KTrussConf ktConf) throws URISyntaxException {
        super(neighborList);
        this.neighborList = neighborList;
        this.k = ktConf.getKt();
        this.ktConf = ktConf;
        String master = ktConf.getSc().master();
        if (master.contains("local"))
            return;
        String masterHost = new URI(ktConf.getSc().master()).getHost();
        this.ktConf.getSc().setCheckpointDir("hdfs://" + masterHost + "/shared/checkpoint");
    }

    public JavaPairRDD <Edge, int[]> genTSet() throws URISyntaxException {
        KCore kCore = new KCore(neighborList, ktConf);

        Triangle triangle = new Triangle(kCore);

        JavaPairRDD <Integer, int[]> fonl = triangle.getOrCreateFonl();

        JavaPairRDD <Integer, int[]> candidates = triangle.createCandidates(fonl);

        return createTSet(fonl, candidates);
    }

    public JavaPairRDD <Edge, int[]> generate() throws URISyntaxException {

        JavaPairRDD <Edge, int[]> tSet = genTSet();
        int numPartitions = tSet.getNumPartitions();

        final int minSup = k - 2;
        Queue <JavaPairRDD <Edge, int[]>> tSetQueue = new LinkedList <>();
        tSetQueue.add(tSet);
        long kTrussDuration = 0;
        int invalidsCount = 0;
        for (int iter = 0; iter < ktConf.getKtMaxIter(); iter++) {

            long t1 = System.currentTimeMillis();

            if ((iter + 1) % CHECKPOINT_ITERATION == 0) {
                tSet.checkpoint();
            }

            // Detect invalid edges by comparing the support of triangle vertex set
            JavaPairRDD <Edge, int[]> invalids = tSet.filter(kv -> kv._2[0] < minSup).cache();
            long invalidCount = invalids.count();

            // If no invalid edge is found then the program terminates
            if (invalidCount == 0) {
                break;
            }

            invalidsCount += invalidCount;

            if (tSetQueue.size() > 1)
                tSetQueue.remove().unpersist();


            long t2 = System.currentTimeMillis();
            String msg = "iteration: " + (iter + 1) + ", invalid edge count: " + invalidCount;
            long iterDuration = t2 - t1;
            kTrussDuration += iterDuration;
            log(msg, iterDuration);

            // The edges in the key part of invalids key-values should be removed. So, we detect other
            // edges of their involved triangle from their triangle vertex set. Here, we determine the
            // vertices which should be removed from the triangle vertex set related to the other edges.
            JavaPairRDD <Edge, Iterable <Integer>> invUpdates = invalids.flatMapToPair(kv -> {
                int i = META_LEN;

                Edge e = kv._1;
                List <Tuple2 <Edge, Integer>> out = new ArrayList <>();
                for (; i < kv._2[1]; i++) {
                    if (kv._2[i] == INVALID)
                        continue;
                    out.add(new Tuple2 <>(new Edge(e.v1, kv._2[i]), e.v2));
                    out.add(new Tuple2 <>(new Edge(e.v2, kv._2[i]), e.v1));
                }

                for (; i < kv._2[2]; i++) {
                    if (kv._2[i] == INVALID)
                        continue;
                    out.add(new Tuple2 <>(new Edge(e.v1, kv._2[i]), e.v2));
                    out.add(new Tuple2 <>(new Edge(kv._2[i], e.v2), e.v1));
                }

                for (; i < kv._2[3]; i++) {
                    if (kv._2[i] == INVALID)
                        continue;
                    out.add(new Tuple2 <>(new Edge(kv._2[i], e.v1), e.v2));
                    out.add(new Tuple2 <>(new Edge(kv._2[i], e.v2), e.v1));
                }

                return out.iterator();
            }).groupByKey(numPartitions);

            // Remove the invalid vertices from the triangle vertex set of each remaining (valid) edge.
            tSet = tSet.filter(kv -> kv._2[0] >= minSup).leftOuterJoin(invUpdates)
                    .mapValues(values -> {
                        org.apache.spark.api.java.Optional <Iterable <Integer>> invalidUpdate = values._2;
                        int[] set = values._1;

                        // If no invalid vertex is present for the current edge then return the set value.
                        if (!invalidUpdate.isPresent()) {
                            return set;
                        }

                        IntSet iSet = new IntOpenHashSet();
                        for (int v : invalidUpdate.get()) {
                            iSet.add(v);
                        }

                        for (int i = META_LEN; i < set.length; i++) {
                            if (set[i] == INVALID)
                                continue;
                            if (iSet.contains(set[i])) {
                                set[0]--;
                                set[i] = INVALID;
                            }
                        }

                        // When the triangle vertex iSet has no other element then the current edge should also
                        // be eliminated from the current tvSets.
                        if (set[0] <= 0)
                            return null;

                        return set;
                    }).filter(kv -> kv._2 != null)
                    .persist(StorageLevel.MEMORY_AND_DISK());

            invalids.unpersist();
            tSetQueue.add(tSet);
        }

        log("kTruss duration: " + kTrussDuration + ", invalids: " + invalidsCount);
        return tSet;
    }

    private JavaPairRDD <Edge, int[]> createTSet(JavaPairRDD <Integer, int[]> fonl,
                                                 JavaPairRDD <Integer, int[]> candidates) {
        // Generate kv such that key is an edge and value is its triangle vertices.
        return candidates.cogroup(fonl).mapPartitionsToPair(p -> {
            Map<Edge, IntList> wMap = new HashMap <>();
            Map<Edge, IntList> vMap = new HashMap <>();
            Map<Edge, IntList> uMap = new HashMap <>();

            while (p.hasNext()) {
                Tuple2 <Integer, Tuple2 <Iterable <int[]>, Iterable <int[]>>> t = p.next();
                int[] fVal = t._2._2.iterator().next();
                Arrays.sort(fVal, 1, fVal.length);
                int v = t._1;

                for (int[] cVal : t._2._1) {
                    int u = cVal[0];
                    Edge uv = new Edge(u, v);

                    // The intersection determines triangles which u and vertex are two of their vertices.
                    // Always generate and edge (u, vertex) such that u < vertex.
                    int fi = 1;
                    int ci = 1;
                    while (fi < fVal.length && ci < cVal.length) {
                        if (fVal[fi] < cVal[ci])
                            fi++;
                        else if (fVal[fi] > cVal[ci])
                            ci++;
                        else {
                            int w = fVal[fi];
                            Edge uw = new Edge(u, w);
                            Edge vw = new Edge(v, w);
                            wMap.computeIfAbsent(uv, k -> new IntArrayList()).add(w);
                            vMap.computeIfAbsent(uw, k -> new IntArrayList()).add(v);
                            uMap.computeIfAbsent(vw, k -> new IntArrayList()).add(u);
                            fi++;
                            ci++;
                        }
                    }
                }
            }
            List <Tuple2 <Edge, int[]>> out = new ArrayList <>();
            fillOutput(wMap, vMap, uMap, out);
            fillOutput(vMap, wMap, uMap, out);
            fillOutput(uMap, wMap, vMap, out);

            return out.iterator();
        }).groupByKey()
                .mapValues(values -> {
                    int sw = 0, sv = 0, su = 0;
                    List <VSign> list = new ArrayList <>();
                    for (int[] value : values) {
                        int wSize = value[0];
                        sw += wSize;
                        int offset = 1;
                        for(; offset < wSize; offset ++) {
                            list.add(new VSign(value[offset], W_UVW));
                        }

                        int vSize = value[offset];
                        sv += vSize;
                        for(; offset < vSize; offset ++) {
                            list.add(new VSign(value[offset], V_UVW));
                        }

                        int uSize = value[offset];
                        su += uSize;
                        for(; offset < uSize; offset ++) {
                            list.add(new VSign(value[offset], U_UVW));
                        }
                    }

                    int offsetW = META_LEN;
                    int offsetV = sw + META_LEN;
                    int offsetU = sw + sv + META_LEN;
                    int[] set = new int[META_LEN + list.size()];
                    set[0] = list.size();  // support of edge
                    set[1] = offsetW + sw;  // exclusive max offset of w
                    set[2] = offsetV + sv;  // exclusive max offset of vertex
                    set[3] = offsetU + su;  // exclusive max offset of u

                    for (VSign vs : list) {
                        if (vs.sign == W_UVW)
                            set[offsetW++] = vs.vertex;
                        else if (vs.sign == V_UVW)
                            set[offsetV++] = vs.vertex;
                        else
                            set[offsetU++] = vs.vertex;
                    }

                    return set;
                }).persist(StorageLevel.MEMORY_AND_DISK());
    }

    private static void fillOutput(Map <Edge, IntList> wMap, Map <Edge, IntList> vMap, Map <Edge, IntList> uMap, List <Tuple2 <Edge, int[]>> out) {
        for (Edge edge : wMap.keySet()) {
            IntList outList = new IntArrayList();

            IntList wList = wMap.get(edge);
            outList.add(wList.size());
            outList.addAll(wList);

            IntList vList = vMap.remove(edge);
            outList.add(vList.size());
            outList.addAll(vList);

            IntList uList = uMap.remove(edge);
            outList.add(uList.size());
            outList.addAll(uList);

            out.add(new Tuple2 <>(edge, outList.toIntArray()));
        }
    }

    public static void main(String[] args) throws URISyntaxException {
//        Logger.getLogger("org.apache.spar").setLevel(Level.INFO);
        long t1 = System.currentTimeMillis();

        KTrussConf ktConf = new KTrussConf(new ArgumentReader(args));
        ktConf.init();

        EdgeLoader edgeLoader = new EdgeLoader(ktConf);
        NeighborList neighborList = new NeighborList(edgeLoader);

        KTrussTSet kTrussTSet = new KTrussTSet(neighborList, ktConf);
        JavaPairRDD <Edge, int[]> subgraph = kTrussTSet.generate();
        long t2 = System.currentTimeMillis();
        log("KTruss edge count: " + subgraph.count(), t1, t2);

        kTrussTSet.close();
    }
}

