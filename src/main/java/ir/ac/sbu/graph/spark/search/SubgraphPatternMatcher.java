package ir.ac.sbu.graph.spark.search;

import ir.ac.sbu.graph.spark.EdgeLoader;
import ir.ac.sbu.graph.spark.NeighborList;
import ir.ac.sbu.graph.spark.SparkApp;
import ir.ac.sbu.graph.spark.SparkAppConf;
import ir.ac.sbu.graph.spark.search.fonl.creator.LabelTriangleFonl;
import ir.ac.sbu.graph.spark.search.fonl.creator.LocalFonlCreator;
import ir.ac.sbu.graph.spark.search.fonl.creator.TriangleFonl;
import ir.ac.sbu.graph.spark.search.fonl.local.QFonl;
import ir.ac.sbu.graph.spark.search.fonl.local.Subquery;
import ir.ac.sbu.graph.spark.search.fonl.value.LabelDegreeTriangleFonlValue;
import ir.ac.sbu.graph.spark.search.patterns.PatternReaderUtils;
import it.unimi.dsi.fastutil.ints.*;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.broadcast.Broadcast;
import scala.Tuple2;

import java.io.FileNotFoundException;
import java.util.*;

import static ir.ac.sbu.graph.spark.search.patterns.PatternDebugUtils.printMatches;
import static ir.ac.sbu.graph.spark.search.patterns.PatternDebugUtils.printSubMatches;

public class SubgraphPatternMatcher extends SparkApp {
    private SearchConfig searchConfig;
    private SparkAppConf sparkConf;
    private EdgeLoader edgeLoader;
    private JavaPairRDD<Integer, String> labels;

    public SubgraphPatternMatcher(SearchConfig searchConfig, SparkAppConf sparkConf, EdgeLoader edgeLoader,
                                  JavaPairRDD <Integer, String> labels) {
        super(edgeLoader);
        this.searchConfig = searchConfig;
        this.sparkConf = sparkConf;
        this.edgeLoader = edgeLoader;
        this.labels = labels;
    }

    public long search(QFonl qFonl) {
        List<Subquery> subQueries = LocalFonlCreator.getSubQueries(qFonl);
        if (subQueries.isEmpty())
            return 0;
        Queue<Subquery> queue = new LinkedList<>(subQueries);

        NeighborList neighborList = new NeighborList(edgeLoader);
        TriangleFonl triangleFonl = new TriangleFonl(neighborList);
        LabelTriangleFonl labelTriangleFonl = new LabelTriangleFonl(triangleFonl, labels);
        JavaPairRDD <Integer, LabelDegreeTriangleFonlValue> ldtFonlRDD = labelTriangleFonl.create();

        Broadcast<Subquery> broadcast = sparkConf.getSc().broadcast(queue.remove());

        final int splitSize = qFonl.splits.length;
        JavaPairRDD <Integer, Tuple2<long[], int[][]>> matches = getSubMatches(ldtFonlRDD, broadcast)
                .mapValues(v -> {
                    int[][] match = new int[splitSize][];
                    match[0] = v._2;
                    long count = v._1;
                    long[] counts = new long[splitSize];
                    counts[0] = count;
                    return new Tuple2 <>(counts, match);
                });

        int qIndex = 0;
        while (!queue.isEmpty()) {
            qIndex ++;
            Subquery subquery = queue.remove();
            broadcast = sparkConf.getSc().broadcast(subquery);
            final int splitIndex = qIndex;


            long count = matches.count();
            System.out.println("partial count: " + count);
            if (count == 0) {
                System.out.println("No match found");
                return 0;
            }

            JavaPairRDD <Integer, Tuple2 <Long, int[]>> subMatches = getSubMatches(ldtFonlRDD, broadcast);
            if(searchConfig.isSingle())
                printSubMatches("SubMatch (" + splitIndex + ")", subMatches);

            matches = matches.join(subMatches).mapValues(val -> {
                // val._2 => (count, keyVertices)
                IntSet set = new IntOpenHashSet(val._2._2);
                val._1._1[splitIndex] = val._2._1;
                val._1._2[splitIndex] = set.toIntArray();
                return val._1;
            }).cache();

            System.out.println("matches count: " + matches.count());
            if (searchConfig.isSingle())
                printMatches("matches (" + splitIndex + ")", matches);
        }

        return matches.map(kv -> {
            long matchCount = 1;
            for (long subCount : kv._2._1) {
                matchCount *= subCount;
            }
            return matchCount;
        }).reduce((a, b) -> a + b);
    }

    private JavaPairRDD <Integer, Tuple2 <Long, int[]>> getSubMatches(JavaPairRDD <Integer, LabelDegreeTriangleFonlValue> tFonl,
                                                                      Broadcast <Subquery> broadcast) {
        return tFonl.flatMapToPair(kv -> {

            List <Tuple2 <Integer, Tuple2 <Long, Integer>>> out = new ArrayList<>();
            Subquery subquery = broadcast.getValue();

            // vertex to count
            Int2LongMap counters = kv._2.matches(kv._1, subquery);

            for (Map.Entry <Integer, Long> entry : counters.entrySet()) {
                // (vertex, (count, keyVertex))
                out.add(new Tuple2 <>(entry.getKey(), new Tuple2 <>(entry.getValue(), kv._1)));
            }

            return out.iterator();

        }).groupByKey(tFonl.getNumPartitions())
                .mapValues(val -> {
                    IntSortedSet sortedSet = new IntAVLTreeSet();
                    long count = 0;
                    // (count, keyVertex)
                    for (Tuple2 <Long, Integer> vId : val) {
                        count += vId._1;
                        sortedSet.add(vId._2.intValue());
                    }
                    return new Tuple2 <>(count, sortedSet.toIntArray());
                }).cache();
    }


    public static void main(String[] args) throws FileNotFoundException {
        SearchConfig searchConfig = SearchConfig.load(args[0]);
        SparkAppConf sparkConf = searchConfig.getSparkAppConf();
        sparkConf.init();

        QFonl qFonl = PatternReaderUtils.loadSample(searchConfig.getSampleName());
        System.out.println("qFonl" + qFonl);

        EdgeLoader edgeLoader = new EdgeLoader(sparkConf);

        JavaPairRDD <Integer, String> labels = PatternCounter.getLabels(sparkConf.getSc(),
                searchConfig.getGraphLabelPath(), sparkConf.getPartitionNum());

    }
}
