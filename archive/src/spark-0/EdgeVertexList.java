package ir.ac.sbu.graph.ktruss.others;

import ir.ac.sbu.graph.utils.GraphUtils;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import scala.Tuple2;

import java.util.*;

public class EdgeVertexList {

    public static void main(String[] args) {
        String inputPath = "/home/mehdi/graph-data/com-amazon.ungraph.txt";
//        String graphInputPath = "/home/mehdi/graph-data/cit-Patents.txt";
        if (args.length > 0)
            inputPath = args[0];

        int partition = 10;
        if (args.length > 1)
            partition = Integer.parseInt(args[1]);

        int k = 4; // k-truss
        if (args.length > 2)
            k = Integer.parseInt(args[2]);
        final int support = k - 2;

        SparkConf conf = new SparkConf();
        if (args.length == 0)
            conf.setMaster("local[2]");
        GraphUtils.setAppName(conf, "KTruss-EdgeVertexList-" + k, partition, inputPath);
        conf.registerKryoClasses(new Class[]{GraphUtils.VertexDegree.class, long[].class, List.class});
        conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");
        JavaSparkContext sc = new JavaSparkContext(conf);

        long start = System.currentTimeMillis();

        JavaPairRDD<Tuple2<Long, Long>, List<Long>> edgeNodes = RebuildTriangles.listEdgeNodes(sc, inputPath, partition);

        int iteration = 0;
        boolean stop = false;
        do {
            log("iteration: " + ++iteration);

            log("edgeNodes: " + edgeNodes.count());

            JavaPairRDD<Tuple2<Long, Long>, List<Long>> invalidEdges =
                edgeNodes.filter(en -> en._2.size() < support);

            long invalidEdgesCount = invalidEdges.count();
            log("invalidEdgeCount: " + invalidEdgesCount);
            if (invalidEdgesCount == 0) {
                stop = true;
                break;
            }

            JavaPairRDD<Tuple2<Long, Long>, Long> edgeInvalidNodes = invalidEdges
                .flatMapToPair(e -> {
                    List<Tuple2<Tuple2<Long, Long>, Long>> edges = new ArrayList<>();
                    Tuple2<Long, Long> invalidEdge = e._1;

                    for (Long node : e._2) {
                        if (node < invalidEdge._1)
                            edges.add(new Tuple2<>(new Tuple2<>(node, invalidEdge._1), invalidEdge._2));
                        else
                            edges.add(new Tuple2<>(new Tuple2<>(invalidEdge._1, node), invalidEdge._2));

                        if (node < invalidEdge._2)
                            edges.add(new Tuple2<>(new Tuple2<>(node, invalidEdge._2), invalidEdge._1));
                        else
                            edges.add(new Tuple2<>(new Tuple2<>(invalidEdge._2, node), invalidEdge._1));
                    }
                    return edges.iterator();
                });

            JavaPairRDD<Tuple2<Long, Long>, List<Long>> newEdgeNodes = edgeNodes
                .cogroup(edgeInvalidNodes, partition)
                .flatMapToPair(t -> {
                    Iterator<List<Long>> it = t._2._1.iterator();
                    if (!it.hasNext())
                        return Collections.emptyIterator();

                    List<Long> nodes = it.next();

                    if (nodes.size() < support)
                        return Collections.emptyIterator();

                    for (Long n : t._2._2) {
                        nodes.remove(n);
                    }

                    if (nodes.size() == 0)
                        return Collections.emptyIterator();

                    return Collections.singleton(new Tuple2<>(t._1,  nodes)).iterator();
                }).repartition(partition).cache();

            edgeNodes.unpersist();
            edgeNodes = newEdgeNodes;
        } while (!stop);

        long duration = System.currentTimeMillis() - start;
        JavaRDD<Tuple2<Long, Long>> edges = edgeNodes.map(t -> t._1);
        long edgeCount = edges.count();
        logDuration("KTruss Edge Count: " + edgeCount, duration);
        sc.close();
    }

    static void log(String text) {
        System.out.println("KTRUSS [" + new Date() + "] " + text);
    }

    static void logDuration(String text, long millis) {
        log(text + ", duration " + millis / 1000 + " sec");
    }
}
